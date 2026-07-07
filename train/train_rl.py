"""
train_rl.py
===========
Phase 2 — Reinforcement Learning fine-tuning with PPO via stable-baselines3.

The environment (BoardEnv) wraps board_simulator.py into a gymnasium.Env.
Training starts from the IL checkpoint so the policy is already warm.

Usage:
    python train_rl.py --il_checkpoint il_checkpoint.pt \
                       --total_steps 1_000_000 \
                       --output rl_checkpoint.pt

Reward design (matches the Kotlin beam search scoring):
    step penalty   : −15 per move
    episode reward : +combos × 50_000  when the episode ends (STOP or max steps)
    zero-combo penalty: −500 if STOP issued with 0 combos
"""

from __future__ import annotations
import argparse
import random
from typing import Any

import numpy as np
import gymnasium as gym
import torch
from stable_baselines3 import PPO
from stable_baselines3.common.policies import ActorCriticPolicy
from stable_baselines3.common.env_util import make_vec_env
from stable_baselines3.common.callbacks import EvalCallback, CheckpointCallback

from board_simulator import (
    random_board, count_combos, count_weighted_score, apply_swap, encode_state,
    ACTION_STOP, DIR_R, DIR_C, ROWS, COLS, NUM_ACTIONS, OrbType, ORB_LABELS
)


# ── Custom gymnasium environment ──────────────────────────────────────────────

class BoardEnv(gym.Env):
    """
    One episode = one full drag on a randomly generated board.
    The episode ends when the policy outputs ACTION_STOP or max_steps is reached.
    """

    metadata = {"render_modes": []}

    def __init__(self, max_steps: int = 35,
                 priority_type: OrbType | None = None,
                 priority_weight: float = 3.0):
        super().__init__()
        self.max_steps       = max_steps
        self.priority_type   = priority_type
        self.priority_weight = priority_weight
        self.observation_space = gym.spaces.Box(
            low=0.0, high=1.0, shape=(226,), dtype=np.float32
        )
        self.action_space = gym.spaces.Discrete(NUM_ACTIONS)

        self.board:       np.ndarray | None = None
        self.row:         int  = 0
        self.col:         int  = 0
        self.prev_action: int  = ACTION_STOP
        self.step_count:  int  = 0

    def reset(self, *, seed=None, options=None):
        super().reset(seed=seed)
        self.board = random_board()
        self.row   = random.randint(0, ROWS - 1)
        self.col   = random.randint(0, COLS - 1)
        self.prev_action = ACTION_STOP
        self.step_count  = 0
        obs = encode_state(self.board, self.row, self.col, self.prev_action)
        return obs, {}

    def step(self, action: int):
        assert self.board is not None, "call reset() first"
        self.step_count += 1
        reward    = -15.0  # step penalty
        terminated = False
        truncated  = False

        if action == ACTION_STOP or self.step_count >= self.max_steps:
            # End the episode; award the weighted combo bonus
            weighted = count_weighted_score(self.board, self.priority_type, self.priority_weight)
            reward += weighted * 50_000.0
            if count_combos(self.board) == 0:
                reward -= 500.0
            terminated = True
        else:
            nr = self.row + DIR_R[action]
            nc = self.col + DIR_C[action]
            if 0 <= nr < ROWS and 0 <= nc < COLS:
                self.board = apply_swap(self.board, self.row, self.col, nr, nc)
                self.row, self.col = nr, nc
            else:
                # Out-of-bounds move: treat like STOP
                combos = count_combos(self.board)
                reward += combos * 50_000.0 - 200.0  # small penalty for invalid move
                terminated = True

        self.prev_action = action
        obs = encode_state(self.board, self.row, self.col, self.prev_action)
        return obs, reward, terminated, truncated, {}


# ── Weight injection from IL checkpoint ───────────────────────────────────────

def inject_il_weights(ppo_model: PPO, il_path: str, device: str) -> None:
    """Copies shared backbone + policy head weights from IL checkpoint into PPO policy."""
    from model import OrbPolicy
    il_model = OrbPolicy()
    il_model.load_state_dict(torch.load(il_path, map_location=device))

    ppo_policy = ppo_model.policy
    with torch.no_grad():
        # Shared layers
        ppo_policy.mlp_extractor.policy_net[0].weight.copy_(il_model.shared[0].weight)
        ppo_policy.mlp_extractor.policy_net[0].bias.copy_(  il_model.shared[0].bias)
        ppo_policy.mlp_extractor.policy_net[2].weight.copy_(il_model.shared[2].weight)
        ppo_policy.mlp_extractor.policy_net[2].bias.copy_(  il_model.shared[2].bias)
        # Policy head
        ppo_policy.action_net.weight.copy_(il_model.policy_head.weight)
        ppo_policy.action_net.bias.copy_(  il_model.policy_head.bias)
    print("IL weights injected into PPO policy.")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--il_checkpoint",  type=str,   default="il_checkpoint.pt")
    parser.add_argument("--total_steps",    type=int,   default=1_000_000)
    parser.add_argument("--n_envs",         type=int,   default=8)
    parser.add_argument("--max_steps",      type=int,   default=35)
    parser.add_argument("--output",         type=str,   default="rl_checkpoint.pt")
    parser.add_argument("--device",         type=str,   default="auto")
    parser.add_argument("--priority_type",  type=str,   default="通用",
                        choices=list(ORB_LABELS.keys()))
    parser.add_argument("--priority_weight",type=float, default=3.0)
    args = parser.parse_args()

    priority_type = ORB_LABELS.get(args.priority_type)
    env_kwargs = {"max_steps": args.max_steps,
                  "priority_type": priority_type,
                  "priority_weight": args.priority_weight}
    train_env  = make_vec_env(BoardEnv, n_envs=args.n_envs, env_kwargs=env_kwargs)
    eval_env   = make_vec_env(BoardEnv, n_envs=4,           env_kwargs=env_kwargs)

    # SB3 PPO with a net_arch that matches our model (256→128)
    policy_kwargs = dict(
        net_arch=dict(pi=[256, 128], vf=[256, 128]),
        activation_fn=torch.nn.ReLU,
    )

    ppo = PPO(
        policy         = "MlpPolicy",
        env            = train_env,
        learning_rate  = 3e-4,
        n_steps        = 512,
        batch_size     = 256,
        n_epochs       = 10,
        gamma          = 0.99,
        gae_lambda     = 0.95,
        clip_range     = 0.2,
        ent_coef       = 0.01,
        verbose        = 1,
        device         = args.device,
        policy_kwargs  = policy_kwargs,
    )

    # Warm-start from IL checkpoint
    import os
    if os.path.exists(args.il_checkpoint):
        try:
            inject_il_weights(ppo, args.il_checkpoint, str(ppo.device))
        except Exception as e:
            print(f"Warning: could not inject IL weights ({e}); training from scratch.")
    else:
        print(f"IL checkpoint not found ({args.il_checkpoint}); training from scratch.")

    callbacks = [
        EvalCallback(eval_env, best_model_save_path="./best_rl/",
                     log_path="./rl_logs/", eval_freq=10_000,
                     n_eval_episodes=50, deterministic=True, verbose=1),
        CheckpointCallback(save_freq=50_000, save_path="./rl_checkpoints/",
                           name_prefix="ppo_orb"),
    ]

    ppo.learn(total_timesteps=args.total_steps, callback=callbacks, progress_bar=True)

    # Save final weights in raw PyTorch format (for export_tflite.py)
    torch.save(ppo.policy.state_dict(), args.output)
    print(f"RL training complete.  Saved to {args.output}")


if __name__ == "__main__":
    main()
