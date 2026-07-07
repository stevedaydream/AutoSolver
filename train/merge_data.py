"""
merge_data.py
=============
Merges multiple IL training NPZ files (states + actions) into one.

Each input file must contain:
    states  : float32 (N, 226)
    actions : int64   (N,)

Usage:
    python merge_data.py il_data.npz sim_data.npz -o merged_data.npz

    # Merge several attribute-specific files into one general dataset:
    python merge_data.py water.npz fire.npz wood.npz light.npz dark.npz heart.npz -o all_data.npz

    # Shuffle the merged result (recommended before training):
    python merge_data.py a.npz b.npz --shuffle --seed 42 -o merged.npz

    # Limit total samples (useful for balancing datasets):
    python merge_data.py big.npz small.npz --max_samples 200000 -o balanced.npz
"""

from __future__ import annotations
import argparse
import sys
import numpy as np


ACTION_NAMES = ["UP", "DOWN", "LEFT", "RIGHT", "STOP"]


def load_npz(path: str) -> tuple[np.ndarray, np.ndarray]:
    """Loads a training NPZ file and returns (states, actions)."""
    data = np.load(path)
    if "states" not in data or "actions" not in data:
        raise ValueError(f"{path}: missing 'states' or 'actions' array")
    states  = data["states"].astype(np.float32)
    actions = data["actions"].astype(np.int64)
    if states.ndim != 2 or states.shape[1] != 226:
        raise ValueError(f"{path}: states shape {states.shape} — expected (N, 226)")
    if actions.ndim != 1 or len(actions) != len(states):
        raise ValueError(f"{path}: actions length {len(actions)} != states length {len(states)}")
    return states, actions


def action_distribution(actions: np.ndarray) -> str:
    total = len(actions)
    parts = []
    for i, name in enumerate(ACTION_NAMES):
        count = int(np.sum(actions == i))
        pct   = count / total * 100 if total > 0 else 0.0
        parts.append(f"{name}:{count}({pct:.1f}%)")
    return "  ".join(parts)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Merge multiple IL training NPZ files into one."
    )
    parser.add_argument("inputs",        nargs="+",     help="Input NPZ files to merge")
    parser.add_argument("-o", "--output", default="merged_data.npz",
                        help="Output NPZ file (default: merged_data.npz)")
    parser.add_argument("--shuffle",     action="store_true",
                        help="Shuffle samples after merging")
    parser.add_argument("--seed",        type=int, default=42,
                        help="Random seed for shuffle (default: 42)")
    parser.add_argument("--max_samples", type=int, default=0,
                        help="Limit total samples (0 = no limit, applied after shuffle)")
    args = parser.parse_args()

    all_states:  list[np.ndarray] = []
    all_actions: list[np.ndarray] = []
    total_before = 0

    print(f"Merging {len(args.inputs)} file(s)...\n")
    for path in args.inputs:
        try:
            states, actions = load_npz(path)
        except (FileNotFoundError, ValueError) as e:
            print(f"  [ERROR] {e}", file=sys.stderr)
            sys.exit(1)

        n = len(states)
        total_before += n
        all_states.append(states)
        all_actions.append(actions)
        dist = action_distribution(actions)
        print(f"  {path}: {n:,} samples")
        print(f"    actions → {dist}")

    if not all_states:
        print("No data loaded.", file=sys.stderr)
        sys.exit(1)

    merged_states  = np.concatenate(all_states,  axis=0)
    merged_actions = np.concatenate(all_actions, axis=0)
    print(f"\nMerged total: {total_before:,} samples")

    if args.shuffle:
        rng  = np.random.default_rng(args.seed)
        perm = rng.permutation(len(merged_states))
        merged_states  = merged_states[perm]
        merged_actions = merged_actions[perm]
        print(f"Shuffled with seed={args.seed}")

    if args.max_samples > 0 and len(merged_states) > args.max_samples:
        merged_states  = merged_states[:args.max_samples]
        merged_actions = merged_actions[:args.max_samples]
        print(f"Trimmed to {args.max_samples:,} samples")

    n_final = len(merged_states)
    np.savez_compressed(args.output,
                        states=merged_states,
                        actions=merged_actions)

    print(f"\nSaved → {args.output}")
    print(f"  states  : {merged_states.shape}  dtype={merged_states.dtype}")
    print(f"  actions : {merged_actions.shape}  dtype={merged_actions.dtype}")
    print(f"  action distribution → {action_distribution(merged_actions)}")
    print(f"\nDone.  {n_final:,} samples ready for train_il.py.")


if __name__ == "__main__":
    main()
