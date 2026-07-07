"""
train_il.py
===========
Phase 1 — Imitation Learning (IL).

Trains the policy network to mimic beam-search decisions using cross-entropy loss.

Usage:
    python train_il.py --data il_data.npz --epochs 30 --output il_checkpoint.pt

Typical results to expect:
    • Validation accuracy  ≥ 70 % after ~20 epochs
    • Loss plateau usually around 0.6–1.0 (action space = 5, entropy of random ≈ 1.61)
"""

from __future__ import annotations
import argparse
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
import numpy as np
import torch
import torch.nn as nn
import torch.optim as optim
from torch.utils.data import DataLoader, TensorDataset, random_split
from model import OrbPolicy


def train(data_path: str, epochs: int, batch_size: int, lr: float,
          val_split: float, output: str, device: str) -> None:

    # ── Load data ──────────────────────────────────────────────────────────────
    data    = np.load(data_path)
    states  = torch.tensor(data["states"],  dtype=torch.float32)
    actions = torch.tensor(data["actions"], dtype=torch.long)

    dataset     = TensorDataset(states, actions)
    val_size    = int(len(dataset) * val_split)
    train_size  = len(dataset) - val_size
    train_ds, val_ds = random_split(dataset, [train_size, val_size],
                                    generator=torch.Generator().manual_seed(42))

    train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True,  num_workers=0)
    val_loader   = DataLoader(val_ds,   batch_size=batch_size, shuffle=False, num_workers=0)

    print(f"Dataset: {len(dataset)} samples  |  train {train_size}  val {val_size}")

    # ── Model & optimiser ──────────────────────────────────────────────────────
    dev   = torch.device(device if torch.cuda.is_available() else "cpu")
    model = OrbPolicy().to(dev)
    opt   = optim.Adam(model.parameters(), lr=lr)
    sched = optim.lr_scheduler.CosineAnnealingLR(opt, T_max=epochs)
    loss_fn = nn.CrossEntropyLoss()

    best_val_acc = 0.0

    for epoch in range(1, epochs + 1):
        # ── Training ──────────────────────────────────────────────────────────
        model.train()
        train_loss = 0.0
        for s, a in train_loader:
            s, a = s.to(dev), a.to(dev)
            logits, _ = model(s)
            loss = loss_fn(logits, a)
            opt.zero_grad(); loss.backward(); opt.step()
            train_loss += loss.item() * len(s)
        train_loss /= train_size

        # ── Validation ────────────────────────────────────────────────────────
        model.eval()
        correct = total = 0
        val_loss = 0.0
        with torch.no_grad():
            for s, a in val_loader:
                s, a = s.to(dev), a.to(dev)
                logits, _ = model(s)
                val_loss += loss_fn(logits, a).item() * len(s)
                preds = logits.argmax(dim=1)
                correct += (preds == a).sum().item()
                total   += len(a)
        val_loss /= val_size
        val_acc   = correct / total

        sched.step()
        print(f"Epoch {epoch:3d}/{epochs}  "
              f"train_loss={train_loss:.4f}  "
              f"val_loss={val_loss:.4f}  "
              f"val_acc={val_acc:.3f}")

        if val_acc > best_val_acc:
            best_val_acc = val_acc
            torch.save(model.state_dict(), output)
            print(f"  ✓ saved checkpoint (val_acc={val_acc:.3f})")

    print(f"\nIL training done.  Best val_acc={best_val_acc:.3f}  → {output}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data",       type=str,   default="il_data.npz")
    parser.add_argument("--epochs",     type=int,   default=30)
    parser.add_argument("--batch_size", type=int,   default=512)
    parser.add_argument("--lr",         type=float, default=1e-3)
    parser.add_argument("--val_split",  type=float, default=0.1)
    parser.add_argument("--output",     type=str,   default="il_checkpoint.pt")
    parser.add_argument("--device",     type=str,   default="cuda")
    args = parser.parse_args()
    train(args.data, args.epochs, args.batch_size, args.lr,
          args.val_split, args.output, args.device)


if __name__ == "__main__":
    main()
