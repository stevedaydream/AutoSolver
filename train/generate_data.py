"""
generate_data.py
================
Generates Imitation Learning (IL) training data by running beam search on
random boards and converting each step to a (state, action) pair.

Usage:
    python generate_data.py --num_boards 20000 --output il_data.npz

Output (il_data.npz):
    states  : float32 (N, 226)
    actions : int64   (N,)
"""

from __future__ import annotations
import argparse
import random
import numpy as np
from tqdm import tqdm
from board_simulator import (
    random_board, beam_search, apply_swap, encode_state,
    ACTION_STOP, DIR_R, DIR_C, ROWS, COLS, OrbType, ORB_LABELS
)


def path_to_il_samples(board: np.ndarray, path: list[tuple[int, int]]
                        ) -> list[tuple[np.ndarray, int]]:
    """
    Converts a beam-search path into a list of (encoded_state, action) pairs.

    At each step i the held-orb is at path[i]; the action taken was the
    direction that moves it to path[i+1].  The board is updated by simulating
    each swap so the policy sees the evolving state, not the initial board.
    """
    samples = []
    if len(path) < 2:
        return samples

    b = board.copy()
    prev_action = ACTION_STOP  # "NONE" at the start of the drag

    for i in range(len(path) - 1):
        r,  c  = path[i]
        nr, nc = path[i + 1]

        # Determine action from position delta
        dr, dc = nr - r, nc - c
        action = next(
            (a for a in range(4) if DIR_R[a] == dr and DIR_C[a] == dc),
            ACTION_STOP
        )

        state = encode_state(b, r, c, prev_action)
        samples.append((state, action))

        # Simulate the swap so the board state advances correctly
        b = apply_swap(b, r, c, nr, nc)
        prev_action = action

    # Final position: STOP action (the policy should learn to stop here)
    r, c = path[-1]
    state = encode_state(b, r, c, prev_action)
    samples.append((state, ACTION_STOP))

    return samples


def generate(num_boards: int, max_steps: int, beam_width: int,
             priority_type: OrbType | None = None, priority_weight: float = 3.0,
             verbose: bool = True) -> tuple[np.ndarray, np.ndarray]:
    all_states  = []
    all_actions = []
    skipped     = 0

    label = "通用" if priority_type is None else priority_type.name
    desc  = f"Generating IL data [{label} priority]"
    iterable = tqdm(range(num_boards), desc=desc) if verbose else range(num_boards)
    for _ in iterable:
        board = random_board()
        path, combos = beam_search(board, max_steps=max_steps, beam_width=beam_width,
                                   priority_type=priority_type, priority_weight=priority_weight)

        if combos <= 0 or len(path) < 2:
            skipped += 1
            continue

        for state, action in path_to_il_samples(board, path):
            all_states.append(state)
            all_actions.append(action)

    print(f"Generated {len(all_states)} samples from {num_boards - skipped} boards "
          f"({skipped} skipped — no combos found).")
    return (np.array(all_states,  dtype=np.float32),
            np.array(all_actions, dtype=np.int64))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--num_boards",      type=int,   default=20_000)
    parser.add_argument("--max_steps",       type=int,   default=35)
    parser.add_argument("--beam_width",      type=int,   default=45)
    parser.add_argument("--output",          type=str,   default="il_data.npz")
    parser.add_argument("--seed",            type=int,   default=42)
    parser.add_argument("--priority_type",   type=str,   default="通用",
                        choices=list(ORB_LABELS.keys()),
                        help="屬性優先 (通用/水/火/木/光/暗/心)")
    parser.add_argument("--priority_weight", type=float, default=3.0,
                        help="優先屬性的分數倍率 (建議 2.0–5.0)")
    args = parser.parse_args()

    random.seed(args.seed)
    np.random.seed(args.seed)

    priority_type = ORB_LABELS.get(args.priority_type)  # None if "通用"
    states, actions = generate(args.num_boards, args.max_steps, args.beam_width,
                               priority_type=priority_type,
                               priority_weight=args.priority_weight)
    np.savez_compressed(args.output, states=states, actions=actions)
    print(f"Saved to {args.output}  (states: {states.shape}, actions: {actions.shape})")


if __name__ == "__main__":
    main()
