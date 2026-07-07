"""
model.py
========
Policy network for orb-path planning.

Architecture:
  Input  (226)  →  Dense(256, ReLU)  →  Dense(128, ReLU)
                                      →  policy_head(5)   softmax logits
                                      →  value_head(1)    scalar (for PPO)

The policy head outputs raw logits; softmax / argmax is applied at inference time.
"""

from __future__ import annotations
import torch
import torch.nn as nn
import torch.nn.functional as F

INPUT_SIZE  = 226
HIDDEN1     = 256
HIDDEN2     = 128
NUM_ACTIONS = 5   # UP DOWN LEFT RIGHT STOP


class OrbPolicy(nn.Module):
    def __init__(self):
        super().__init__()
        self.shared = nn.Sequential(
            nn.Linear(INPUT_SIZE, HIDDEN1),
            nn.ReLU(),
            nn.Linear(HIDDEN1, HIDDEN2),
            nn.ReLU(),
        )
        self.policy_head = nn.Linear(HIDDEN2, NUM_ACTIONS)
        self.value_head  = nn.Linear(HIDDEN2, 1)

    def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        """Returns (policy_logits, state_value)."""
        h = self.shared(x)
        return self.policy_head(h), self.value_head(h).squeeze(-1)

    def get_action_logits(self, x: torch.Tensor) -> torch.Tensor:
        h = self.shared(x)
        return self.policy_head(h)

    @torch.no_grad()
    def predict(self, x: torch.Tensor) -> int:
        """Greedy argmax action for a single state (1-D tensor)."""
        logits = self.get_action_logits(x.unsqueeze(0))
        return int(logits.argmax(dim=-1).item())
