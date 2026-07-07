"""
board_simulator.py
==================
Python replication of the BoardAnalyzer board-state logic (Kotlin → Python).

Board layout : 5 rows × 6 cols   (row 0 = top, row 4 = bottom)
Orb types    : 0=WATER 1=FIRE 2=WOOD 3=LIGHT 4=DARK 5=HEART 6=UNKNOWN
Actions      : 0=UP  1=DOWN  2=LEFT  3=RIGHT  4=STOP

The swap-only mechanic (no gravity during the drag) is replicated exactly.
Gravity / cascade are NOT simulated here because path planning in the game
ends as soon as the player releases the orb — the board shown to the policy
is always the "in-drag" state.
"""

from __future__ import annotations
import numpy as np
import random
from enum import IntEnum


class OrbType(IntEnum):
    WATER   = 0
    FIRE    = 1
    WOOD    = 2
    LIGHT   = 3
    DARK    = 4
    HEART   = 5
    UNKNOWN = 6


# Human-readable labels for GUI / CLI
ORB_LABELS: dict[str, OrbType] = {
    "通用": None,           # None = no priority (use standard scoring)
    "水":   OrbType.WATER,
    "火":   OrbType.FIRE,
    "木":   OrbType.WOOD,
    "光":   OrbType.LIGHT,
    "暗":   OrbType.DARK,
    "心":   OrbType.HEART,
}

# Model filename for each label
ORB_MODEL_FILES: dict[str, str] = {
    "通用": "orb_policy_general.tflite",
    "水":   "orb_policy_water.tflite",
    "火":   "orb_policy_fire.tflite",
    "木":   "orb_policy_wood.tflite",
    "光":   "orb_policy_light.tflite",
    "暗":   "orb_policy_dark.tflite",
    "心":   "orb_policy_heart.tflite",
}

NUM_ORB_TYPES = 7
ROWS, COLS    = 5, 6
NUM_ACTIONS   = 5   # UP DOWN LEFT RIGHT STOP

ACTION_UP    = 0
ACTION_DOWN  = 1
ACTION_LEFT  = 2
ACTION_RIGHT = 3
ACTION_STOP  = 4

DIR_R = [-1,  1,  0,  0]   # UP DOWN LEFT RIGHT
DIR_C = [ 0,  0, -1,  1]


# ── Board creation ────────────────────────────────────────────────────────────

def random_board(exclude_unknown: bool = True) -> np.ndarray:
    """Returns a (5, 6) int array with random orb types (no UNKNOWN by default)."""
    types = list(OrbType)
    if exclude_unknown:
        types = [t for t in types if t != OrbType.UNKNOWN]
    return np.array([[random.choice(types) for _ in range(COLS)] for _ in range(ROWS)], dtype=np.int8)


def copy_board(board: np.ndarray) -> np.ndarray:
    return board.copy()


# ── Move simulation ───────────────────────────────────────────────────────────

def apply_swap(board: np.ndarray, r1: int, c1: int, r2: int, c2: int) -> np.ndarray:
    """Returns a new board with positions (r1,c1) and (r2,c2) swapped."""
    b = board.copy()
    b[r1, c1], b[r2, c2] = b[r2, c2], b[r1, c1]
    return b


def is_valid_move(action: int, row: int, col: int) -> bool:
    nr = row + DIR_R[action]
    nc = col + DIR_C[action]
    return 0 <= nr < ROWS and 0 <= nc < COLS


# ── Combo counting (mirrors Kotlin calculateCombos exactly) ───────────────────

def _build_match_map(board: np.ndarray) -> np.ndarray:
    """Returns a (5,6) bool array marking every cell that belongs to a 3+ match."""
    match = np.zeros((ROWS, COLS), dtype=bool)

    for r in range(ROWS):
        c = 0
        while c < COLS - 2:
            t = board[r, c]
            if t != OrbType.UNKNOWN and board[r, c+1] == t and board[r, c+2] == t:
                end = c + 2
                while end + 1 < COLS and board[r, end+1] == t:
                    end += 1
                match[r, c:end+1] = True
            c += 1

    for col in range(COLS):
        r = 0
        while r < ROWS - 2:
            t = board[r, col]
            if t != OrbType.UNKNOWN and board[r+1, col] == t and board[r+2, col] == t:
                end = r + 2
                while end + 1 < ROWS and board[end+1, col] == t:
                    end += 1
                match[r:end+1, col] = True
            r += 1

    return match


def count_combos(board: np.ndarray) -> int:
    """
    Counts the number of independent combo groups using horizontal/vertical
    3-in-a-row matching — identical logic to BoardAnalyzer.calculateCombos().
    """
    match   = _build_match_map(board)
    visited = np.zeros((ROWS, COLS), dtype=bool)
    combos  = 0

    for sr in range(ROWS):
        for sc in range(COLS):
            if match[sr, sc] and not visited[sr, sc]:
                combos += 1
                queue = [(sr, sc)]
                visited[sr, sc] = True
                t = board[sr, sc]
                while queue:
                    r, c = queue.pop()
                    for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                        nr, nc = r+dr, c+dc
                        if 0 <= nr < ROWS and 0 <= nc < COLS:
                            if match[nr, nc] and not visited[nr, nc] and board[nr, nc] == t:
                                visited[nr, nc] = True
                                queue.append((nr, nc))
    return combos


def simulate_cascade_score(board: np.ndarray, max_waves: int = 2) -> int:
    """
    Simulates up to max_waves of cascade elimination and returns the total combo count
    across all waves (including the initial board's combos).
    Used as a small bonus in score_for_target to guide beam search toward boards with
    cascade potential.
    """
    b = board.copy()
    total = 0
    for _ in range(max_waves):
        match = _build_match_map(b)
        if not match.any():
            break
        total += count_combos(b)
        b[match] = OrbType.UNKNOWN
        for c in range(COLS):
            col = b[:, c]
            valid = col[col != OrbType.UNKNOWN]
            pad = ROWS - len(valid)
            b[:, c] = np.concatenate([
                np.full(pad, OrbType.UNKNOWN, dtype=np.int8), valid])
    return total


def count_combos_by_type(board: np.ndarray) -> dict[int, int]:
    """
    Returns {orb_type_int: combo_count} for each orb type that has at least one combo.
    Uses the same matching logic as count_combos().
    """
    match   = _build_match_map(board)
    visited = np.zeros((ROWS, COLS), dtype=bool)
    result: dict[int, int] = {}

    for sr in range(ROWS):
        for sc in range(COLS):
            if match[sr, sc] and not visited[sr, sc]:
                t = int(board[sr, sc])
                result[t] = result.get(t, 0) + 1
                queue = [(sr, sc)]
                visited[sr, sc] = True
                while queue:
                    r, c = queue.pop()
                    for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                        nr, nc = r+dr, c+dc
                        if 0 <= nr < ROWS and 0 <= nc < COLS:
                            if match[nr, nc] and not visited[nr, nc] and board[nr, nc] == t:
                                visited[nr, nc] = True
                                queue.append((nr, nc))
    return result


def count_weighted_score(board: np.ndarray,
                         priority_type: OrbType | None = None,
                         priority_weight: float = 3.0) -> float:
    """
    Computes a weighted combo score.
    If priority_type is None, this equals count_combos() (weight 1.0 for all types).
    Otherwise, combos of priority_type get priority_weight× bonus; others get 1×.

    Used both in weighted_beam_search() and in the RL environment reward.
    """
    combos_by_type = count_combos_by_type(board)
    score = 0.0
    for orb_int, n in combos_by_type.items():
        w = priority_weight if (priority_type is not None and orb_int == int(priority_type)) else 1.0
        score += n * w
    return score


# ── Feature encoding (mirrors MLOrbPlanner.encodeBoardState) ─────────────────

def encode_state(board: np.ndarray, row: int, col: int, prev_action: int) -> np.ndarray:
    """
    Returns a 226-element float32 array:
      [board 5×6×7 one-hot] [row 5-hot] [col 6-hot] [prev_action 5-hot]
    """
    feat = np.zeros(226, dtype=np.float32)
    idx  = 0

    for r in range(ROWS):
        for c in range(COLS):
            t = int(board[r, c])
            if t < 0 or t >= NUM_ORB_TYPES:
                t = OrbType.UNKNOWN
            feat[idx + t] = 1.0
            idx += NUM_ORB_TYPES

    feat[idx + row] = 1.0;  idx += ROWS
    feat[idx + col] = 1.0;  idx += COLS
    feat[idx + prev_action] = 1.0

    return feat


# ── Shape / target scoring ───────────────────────────────────────────────────

TARGET_TYPES = [
    "最高Combo",    # 0  standard max combos
    "屬性優先",     # 1  weighted attribute priority
    "十字",         # 2  cross (+) shape
    "T字",          # 3  T-shape (4 rotations)
    "L字",          # 4  L/J-shape (8 orientations)
    "直排4",        # 5  vertical 4-in-a-column
    "橫排4",        # 6  horizontal 4-in-a-row
    "指定Combo數",  # 7  hit exact N combos
    "全盤消除",     # 8  all orbs matched
]


def _score_cross(board: np.ndarray, attr_type=None) -> int:
    """Bonus for cross (+) pattern: center + 4 orthogonal same-type neighbors."""
    bonus = 0
    for r in range(1, ROWS - 1):
        for c in range(1, COLS - 1):
            t = int(board[r, c])
            if t == OrbType.UNKNOWN:
                continue
            if attr_type is not None and t != int(attr_type):
                continue
            if (board[r-1, c] == t and board[r+1, c] == t and
                    board[r, c-1] == t and board[r, c+1] == t):
                bonus += 1_000_000
    return bonus


def _score_t_shape(board: np.ndarray, attr_type=None) -> int:
    """Bonus for T-shape (all 4 rotations)."""
    bonus = 0
    for r in range(ROWS):
        for c in range(COLS):
            t = int(board[r, c])
            if t == OrbType.UNKNOWN:
                continue
            if attr_type is not None and t != int(attr_type):
                continue
            b = board
            # T↓: horizontal 3-top + vertical 2-down from center
            if r + 2 < ROWS and c + 2 < COLS:
                if (b[r,c]==t and b[r,c+1]==t and b[r,c+2]==t and
                        b[r+1,c+1]==t and b[r+2,c+1]==t):
                    bonus += 500_000
            # T↑: horizontal 3-bottom + vertical 2-up from center
            if r - 2 >= 0 and c + 2 < COLS:
                if (b[r,c]==t and b[r,c+1]==t and b[r,c+2]==t and
                        b[r-1,c+1]==t and b[r-2,c+1]==t):
                    bonus += 500_000
            # T→: vertical 3-left + horizontal 2-right from center
            if r + 2 < ROWS and c + 2 < COLS:
                if (b[r,c]==t and b[r+1,c]==t and b[r+2,c]==t and
                        b[r+1,c+1]==t and b[r+1,c+2]==t):
                    bonus += 500_000
            # T←: vertical 3-right + horizontal 2-left from center
            if r + 2 < ROWS and c - 2 >= 0:
                if (b[r,c]==t and b[r+1,c]==t and b[r+2,c]==t and
                        b[r+1,c-1]==t and b[r+1,c-2]==t):
                    bonus += 500_000
    return bonus


def _score_l_shape(board: np.ndarray, attr_type=None) -> int:
    """Bonus for L/J shape (8 orientations)."""
    bonus = 0
    b = board
    for r in range(ROWS):
        for c in range(COLS):
            t = int(b[r, c])
            if t == OrbType.UNKNOWN:
                continue
            if attr_type is not None and t != int(attr_type):
                continue
            # Vertical stem (r..r+2) with horizontal extension at ends
            if r + 2 < ROWS:
                if c + 1 < COLS and b[r,c]==t and b[r+1,c]==t and b[r+2,c]==t:
                    if b[r+2,c+1]==t: bonus += 500_000   # L-right-bottom
                    if b[r,  c+1]==t: bonus += 500_000   # L-right-top
                if c - 1 >= 0 and b[r,c]==t and b[r+1,c]==t and b[r+2,c]==t:
                    if b[r+2,c-1]==t: bonus += 500_000   # J-left-bottom
                    if b[r,  c-1]==t: bonus += 500_000   # J-left-top
            # Horizontal stem (c..c+2) with vertical extension at ends
            if c + 2 < COLS:
                if r + 1 < ROWS and b[r,c]==t and b[r,c+1]==t and b[r,c+2]==t:
                    if b[r+1,c+2]==t: bonus += 500_000   # L-down-right
                    if b[r+1,c  ]==t: bonus += 500_000   # J-down-left
                if r - 1 >= 0 and b[r,c]==t and b[r,c+1]==t and b[r,c+2]==t:
                    if b[r-1,c+2]==t: bonus += 500_000   # L-up-right
                    if b[r-1,c  ]==t: bonus += 500_000   # J-up-left
    return bonus


def _score_vertical4(board: np.ndarray, attr_type=None) -> int:
    """Bonus for exactly-4 vertical (mirrors Kotlin evaluateAmelioration)."""
    bonus = 0
    for c in range(COLS):
        for r in range(ROWS - 3):
            t = int(board[r, c])
            if t == OrbType.UNKNOWN:
                continue
            if attr_type is not None and t != int(attr_type):
                continue
            if (board[r+1,c]==t and board[r+2,c]==t and board[r+3,c]==t):
                # Ensure it's exactly 4 (not 5)
                above = (r > 0 and board[r-1,c] == t)
                below = (r + 4 < ROWS and board[r+4,c] == t)
                if not above and not below:
                    bonus += 500_000
    return bonus


def _score_horizontal4(board: np.ndarray, attr_type=None) -> int:
    """Bonus for exactly-4 horizontal."""
    bonus = 0
    for r in range(ROWS):
        for c in range(COLS - 3):
            t = int(board[r, c])
            if t == OrbType.UNKNOWN:
                continue
            if attr_type is not None and t != int(attr_type):
                continue
            if (board[r,c+1]==t and board[r,c+2]==t and board[r,c+3]==t):
                left  = (c > 0 and board[r,c-1] == t)
                right = (c + 4 < COLS and board[r,c+4] == t)
                if not left and not right:
                    bonus += 500_000
    return bonus


def score_for_target(board: np.ndarray,
                     target_type: str,
                     attr_type=None,
                     priority_weight: float = 3.0,
                     target_n: int = 5) -> float:
    """
    Unified scoring function for beam search based on user-selected training target.
    Used by both the board simulator preview and the training data generator.
    A cascade bonus (simulate_cascade_score * 8_000) is added to all variants as a
    small hint toward boards with cascade potential; it is smaller than the primary
    combo weight (50_000) so it guides rather than dominates.
    """
    base_combos = count_combos(board)

    if target_type == "最高Combo":
        base_score = float(base_combos * 50_000)

    elif target_type == "屬性優先":
        base_score = count_weighted_score(board, attr_type, priority_weight) * 50_000

    elif target_type == "十字":
        base_score = base_combos * 10_000 + _score_cross(board, attr_type)

    elif target_type == "T字":
        base_score = base_combos * 10_000 + _score_t_shape(board, attr_type)

    elif target_type == "L字":
        base_score = base_combos * 10_000 + _score_l_shape(board, attr_type)

    elif target_type == "直排4":
        base_score = base_combos * 10_000 + _score_vertical4(board, attr_type)

    elif target_type == "橫排4":
        base_score = base_combos * 10_000 + _score_horizontal4(board, attr_type)

    elif target_type == "指定Combo數":
        diff = abs(base_combos - target_n)
        base_score = -diff * 1_000_000 + base_combos * 1_000

    elif target_type == "全盤消除":
        match     = _build_match_map(board)
        unmatched = int(ROWS * COLS - match.sum())
        base_score = -unmatched * 1_000_000 + base_combos * 10_000

    else:
        base_score = float(base_combos * 50_000)

    cascade = simulate_cascade_score(board, max_waves=2)
    return base_score + cascade * 8_000


# ── Beam search (IL label generation) ────────────────────────────────────────

def beam_search(board: np.ndarray,
                max_steps: int = 35,
                beam_width: int = 45,
                start_mode: int = 0,
                priority_type: OrbType | None = None,
                priority_weight: float = 3.0,
                target_type: str = "最高Combo",
                target_n: int = 5,
                hint_path=None) -> tuple[list[tuple[int, int]], int]:
    """
    Beam search that mirrors BoardAnalyzer.calculateOptimalPath() (is8Way=False).

    When priority_type is set, the scoring function weights combos of that type
    by priority_weight×, so the beam search discovers paths that favour that
    attribute — generating attribute-biased training data for IL.

    Returns (path_as_list_of_(row,col), true_combo_count).
    """
    if start_mode == 1:
        starts = [(4, 0)]
    elif start_mode == 2:
        starts = [(4, 5)]
    else:
        starts = [(r, c) for r in range(ROWS) for c in range(COLS)]

    dirs = [(DIR_R[a], DIR_C[a]) for a in range(4)]

    global_best_path   = []
    global_best_score  = -1.0   # weighted score for ranking
    global_best_combos = -1     # true combo count (returned to caller)
    global_best_len    = 999

    def score_board(b):
        return score_for_target(b, target_type, priority_type, priority_weight, target_n)

    # ── hint_path seed: replay the stored path to get its end state, then inject
    #    it into every starting beam with float('-inf') priority so it is always
    #    explored first.  Also update global_best if the hint itself is good.
    hint_entry = None
    if hint_path and len(hint_path) >= 2:
        b = board.copy()
        for i in range(len(hint_path) - 1):
            r1, c1 = hint_path[i]; r2, c2 = hint_path[i + 1]
            b = apply_swap(b, r1, c1, r2, c2)
        hint_ws = score_board(b)
        hint_entry = (float('-inf'), hint_ws, len(hint_path) - 1,
                      hint_path[-1][0], hint_path[-1][1],
                      b.tobytes(), [tuple(p) for p in hint_path])
        hint_true_combos = count_combos(b)
        if hint_ws > global_best_score:
            global_best_score  = hint_ws
            global_best_combos = hint_true_combos
            global_best_len    = len(hint_path) - 1
            global_best_path   = [tuple(p) for p in hint_path]

    for (sr, sc) in starts:
        init_score = score_board(board) * 50_000
        beam = [(-init_score, init_score, 0, sr, sc, board.tobytes(), [(sr, sc)])]
        if hint_entry is not None:
            beam.append(hint_entry)

        local_best_score = global_best_score
        stagnant = 0

        for step in range(max_steps):
            candidates = []
            for neg_s, ws, path_len, r, c, board_bytes, path in beam:
                b = np.frombuffer(board_bytes, dtype=np.int8).reshape(ROWS, COLS).copy()
                for dr, dc in dirs:
                    nr, nc = r + dr, c + dc
                    if not (0 <= nr < ROWS and 0 <= nc < COLS):
                        continue
                    if len(path) >= 2 and path[-2] == (nr, nc):
                        continue
                    nb       = apply_swap(b, r, c, nr, nc)
                    new_ws   = score_board(nb)
                    new_len  = path_len + 1
                    new_score = new_ws * 50_000 - new_len * 15
                    candidates.append((-new_score, new_ws, new_len, nr, nc,
                                       nb.tobytes(), path + [(nr, nc)]))

                    true_combos = count_combos(nb)
                    is_better = (
                        new_ws > global_best_score or
                        (new_ws == global_best_score and new_len < global_best_len)
                    )
                    if is_better:
                        global_best_score  = new_ws
                        global_best_combos = true_combos
                        global_best_len    = new_len
                        global_best_path   = path + [(nr, nc)]

            if not candidates:
                break

            candidates.sort(key=lambda x: (x[0], x[2], x[5][:8]))
            beam = candidates[:beam_width]

            if global_best_score > local_best_score:
                local_best_score = global_best_score
                stagnant = 0
            else:
                stagnant += 1
                if stagnant >= 5:
                    break

    return global_best_path, max(global_best_combos, 0)
