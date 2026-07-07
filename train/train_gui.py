"""
train_gui.py
============
AutoDora ML 訓練管理器 — tkinter GUI

分頁：
  ⚙ 訓練設定   — 參數調整、一鍵訓練、預設方案
  🎲 盤面模擬器 — 互動式盤面編輯、目標設定、匯出訓練資料

使用：
    python train_gui.py
"""

from __future__ import annotations
import json
import math
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox

# ── 路徑設定 ──────────────────────────────────────────────────────────────────

SCRIPT_DIR   = os.path.dirname(os.path.abspath(__file__))
ASSETS_DIR   = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "app", "src", "main", "assets"))
PRESETS_FILE = os.path.join(SCRIPT_DIR, "presets.json")

# ── 屬性定義 ──────────────────────────────────────────────────────────────────

ORB_ATTRS  = ["通用", "水", "火", "木", "光", "暗", "心"]
ORB_COLORS = {
    "通用": "#AAAAAA", "水": "#5DADE2", "火": "#E74C3C",
    "木":   "#2ECC71", "光": "#F4D03F", "暗": "#9B59B6", "心": "#F1948A",
}
ORB_MODEL = {
    "通用": "orb_policy_general.tflite", "水": "orb_policy_water.tflite",
    "火":   "orb_policy_fire.tflite",    "木": "orb_policy_wood.tflite",
    "光":   "orb_policy_light.tflite",   "暗": "orb_policy_dark.tflite",
    "心":   "orb_policy_heart.tflite",
}

# OrbType int values (match board_simulator.OrbType)
ORB_INT = {"水": 0, "火": 1, "木": 2, "光": 3, "暗": 4, "心": 5, "?": 6}
INT_ORB = {v: k for k, v in ORB_INT.items()}
INT_ORB[6] = "?"

# Orb paint colors for board cells (no "通用" — cells use actual types)
CELL_TYPES  = ["水", "火", "木", "光", "暗", "心", "?"]
CELL_COLORS = {
    "水": "#1A6FA8", "火": "#A83228", "木": "#1A7A4A",
    "光": "#A89A10", "暗": "#5C2A88", "心": "#A84070", "?": "#2C3E50",
}
CELL_TEXT_COLORS = {
    "水": "#ADE4FF", "火": "#FFB0A8", "木": "#A8FFD0",
    "光": "#FFF5A0", "暗": "#DDB0FF", "心": "#FFB0D0", "?": "#666666",
}

TARGET_TYPES = ["最高Combo", "屬性優先", "十字", "T字", "L字", "直排4", "橫排4", "指定Combo數", "全盤消除"]

# Targets that have an attribute sub-option
ATTR_TARGETS  = {"屬性優先", "十字", "T字", "L字", "直排4", "橫排4"}
# Targets that have an N value
COUNT_TARGETS = {"指定Combo數"}

DEFAULT_PARAMS: dict = {
    "priority": "通用", "priority_weight": 3.0,
    "num_boards": 20000, "max_steps": 35, "beam_width": 45, "seed": 42,
    "il_epochs": 30, "il_batch": 512, "il_lr": 0.001, "il_val_split": 0.1,
    "rl_total_steps": 1000000, "rl_n_envs": 8,
    "quantize": False, "data_file": "il_data.npz",
    "il_ckpt": "il_checkpoint.pt", "rl_ckpt": "rl_checkpoint.pt", "device": "cuda",
}


# ═══════════════════════════════════════════════════════════════════════════════
#  Main Window
# ═══════════════════════════════════════════════════════════════════════════════

class TrainGUI(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("🤖 AutoDora ML 訓練管理器")
        self.configure(bg="#1E2933")
        self.resizable(True, True)
        self.minsize(960, 660)

        self._proc:  subprocess.Popen | None = None
        self._queue: queue.Queue = queue.Queue()
        self._vars:  dict[str, tk.Variable] = {}

        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TNotebook",        background="#1E2933", borderwidth=0)
        style.configure("TNotebook.Tab",    background="#243342", foreground="#BDC3C7",
                        padding=[12, 6],    font=("Segoe UI", 10, "bold"))
        style.map("TNotebook.Tab",
                  background=[("selected", "#2980B9")],
                  foreground=[("selected", "white")])
        style.configure("Vertical.TScrollbar", background="#243342", troughcolor="#1E2933")

        self._build_ui()
        self._load_presets_list()
        self._refresh_model_list()
        self._poll_output()

    # ── UI skeleton ────────────────────────────────────────────────────────────

    def _build_ui(self):
        tk.Label(self, text="🤖  AutoDora ML 訓練管理器",
                 font=("Segoe UI", 14, "bold"), fg="#ECF0F1", bg="#151E27",
                 pady=6).pack(fill=tk.X)

        nb = ttk.Notebook(self)
        nb.pack(fill=tk.BOTH, expand=True, padx=6, pady=4)

        # Tab 1 — Training settings
        tab_train = tk.Frame(nb, bg="#1E2933")
        nb.add(tab_train, text="⚙  訓練設定")
        self._build_train_tab(tab_train)

        # Tab 2 — Board simulator
        tab_sim = tk.Frame(nb, bg="#1E2933")
        nb.add(tab_sim, text="🎲  盤面模擬器")
        self.sim = BoardSimulatorTab(tab_sim, self)

    # ─────────────────────────────────────────────────────────────────────────
    #  Tab 1 — Training settings (unchanged from previous version)
    # ─────────────────────────────────────────────────────────────────────────

    def _build_train_tab(self, parent):
        body = tk.Frame(parent, bg="#1E2933")
        body.pack(fill=tk.BOTH, expand=True, padx=4, pady=4)

        left = tk.Frame(body, bg="#1E2933", width=360)
        left.pack(side=tk.LEFT, fill=tk.Y, padx=(0, 4))
        left.pack_propagate(False)

        right = tk.Frame(body, bg="#1E2933")
        right.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        self._build_settings_left(left)
        self._build_output_right(right)
        self._build_toolbar(parent)

    def _build_settings_left(self, parent):
        canvas = tk.Canvas(parent, bg="#1E2933", highlightthickness=0)
        sb = ttk.Scrollbar(parent, orient=tk.VERTICAL, command=canvas.yview)
        canvas.configure(yscrollcommand=sb.set)
        sb.pack(side=tk.RIGHT, fill=tk.Y)
        canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        inner = tk.Frame(canvas, bg="#1E2933")
        wid = canvas.create_window((0, 0), window=inner, anchor=tk.NW)
        inner.bind("<Configure>", lambda e: canvas.configure(scrollregion=canvas.bbox("all")))
        canvas.bind("<Configure>", lambda e: canvas.itemconfig(wid, width=e.width))
        self._section_attr(inner)
        self._section_data(inner)
        self._section_il(inner)
        self._section_rl(inner)
        self._section_export(inner)
        self._section_presets(inner)

    def _build_output_right(self, parent):
        out = tk.LabelFrame(parent, text=" 📋 執行輸出 ", bg="#1E2933",
                            fg="#ECF0F1", font=("Segoe UI", 9, "bold"), padx=4, pady=4)
        out.pack(fill=tk.BOTH, expand=True, pady=(0, 4))
        self.console = scrolledtext.ScrolledText(
            out, bg="#0D1117", fg="#B0C4DE", font=("Consolas", 9),
            state=tk.DISABLED, wrap=tk.WORD)
        self.console.pack(fill=tk.BOTH, expand=True)
        self.console.tag_config("ok",   foreground="#2ECC71")
        self.console.tag_config("err",  foreground="#E74C3C")
        self.console.tag_config("info", foreground="#F4D03F")

        prog = tk.Frame(parent, bg="#1E2933")
        prog.pack(fill=tk.X, pady=(0, 4))
        tk.Label(prog, text="進度", fg="#BDC3C7", bg="#1E2933",
                 font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=4)
        self.progress = ttk.Progressbar(prog, mode="indeterminate", length=200)
        self.progress.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=4)
        self.status_lbl = tk.Label(prog, text="待命", fg="#2ECC71",
                                   bg="#1E2933", font=("Segoe UI", 9, "bold"))
        self.status_lbl.pack(side=tk.LEFT, padx=4)

        mdl = tk.LabelFrame(parent, text=" 📁 可用模型 ", bg="#1E2933",
                            fg="#ECF0F1", font=("Segoe UI", 9, "bold"), padx=4, pady=4)
        mdl.pack(fill=tk.X)
        self.model_list = tk.Listbox(mdl, bg="#0D1117", fg="#5DADE2",
                                     font=("Consolas", 9), height=4)
        self.model_list.pack(fill=tk.X)
        br = tk.Frame(mdl, bg="#1E2933")
        br.pack(fill=tk.X, pady=2)
        self._btn(br, "🔄 重新整理", self._refresh_model_list, "#2980B9").pack(side=tk.LEFT, padx=2)
        self._btn(br, "📂 開啟資料夾", self._open_assets_dir,   "#6C3483").pack(side=tk.LEFT, padx=2)

    def _build_toolbar(self, parent):
        bar = tk.Frame(parent, bg="#151E27", pady=6)
        bar.pack(fill=tk.X, side=tk.BOTTOM)
        self._btn(bar, "📊 生成資料",    self._run_generate, "#2980B9").pack(side=tk.LEFT, padx=6)
        self._btn(bar, "🧠 訓練 IL",     self._run_il,       "#1A8754").pack(side=tk.LEFT, padx=6)
        self._btn(bar, "🔬 強化學習 RL", self._run_rl,       "#6C3483").pack(side=tk.LEFT, padx=6)
        self._btn(bar, "📦 匯出 TFLite", self._run_export,   "#C0392B").pack(side=tk.LEFT, padx=6)
        tk.Frame(bar, bg="#151E27", width=20).pack(side=tk.LEFT)
        self._btn(bar, "⚡ 一鍵全流程",  self._run_all,      "#E67E22", bold=True).pack(side=tk.LEFT, padx=6)
        self._btn(bar, "■ 停止",         self._stop,         "#7F8C8D").pack(side=tk.RIGHT, padx=12)

    # ── Section helpers ────────────────────────────────────────────────────────

    def _section(self, parent, title):
        f = tk.LabelFrame(parent, text=f"  {title}  ", bg="#243342",
                          fg="#ECF0F1", font=("Segoe UI", 9, "bold"), padx=8, pady=6)
        f.pack(fill=tk.X, pady=4, padx=4)
        return f

    def _var(self, key, cls, default):
        if key not in self._vars:
            self._vars[key] = cls(value=default)
        return self._vars[key]

    def _int_row(self, p, lbl, k, d):
        self._var(k, tk.IntVar, d); self._input_row(p, lbl, self._vars[k])

    def _float_row(self, p, lbl, k, d):
        self._var(k, tk.DoubleVar, d); self._input_row(p, lbl, self._vars[k])

    def _str_row(self, p, lbl, k, d):
        self._var(k, tk.StringVar, d); self._input_row(p, lbl, self._vars[k])

    def _input_row(self, parent, label, var):
        row = tk.Frame(parent, bg="#243342")
        row.pack(fill=tk.X, pady=2)
        tk.Label(row, text=label, fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9), width=10, anchor=tk.W).pack(side=tk.LEFT, padx=4)
        tk.Entry(row, textvariable=var, bg="#1E2933", fg="#ECF0F1",
                 insertbackground="#ECF0F1", font=("Consolas", 9),
                 relief=tk.FLAT, width=16).pack(side=tk.LEFT, padx=4)

    def _combo_row(self, parent, label, key, values, default):
        self._var(key, tk.StringVar, default)
        row = tk.Frame(parent, bg="#243342")
        row.pack(fill=tk.X, pady=2)
        tk.Label(row, text=label, fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9), width=10, anchor=tk.W).pack(side=tk.LEFT, padx=4)
        ttk.Combobox(row, textvariable=self._vars[key], values=values,
                     state="readonly", width=12).pack(side=tk.LEFT, padx=4)

    @staticmethod
    def _btn(parent, text, cmd, color, bold=False):
        return tk.Button(parent, text=text, command=cmd, bg=color, fg="white",
                         font=("Segoe UI", 9, "bold" if bold else "normal"),
                         relief=tk.FLAT, padx=8, pady=4,
                         activebackground=color, activeforeground="white")

    # ── Settings sections ──────────────────────────────────────────────────────

    def _section_attr(self, parent):
        f = self._section(parent, "🎯 屬性優先")
        self._var("priority", tk.StringVar, "通用")
        af = tk.Frame(f, bg="#243342"); af.pack(fill=tk.X, pady=4)
        self._attr_btns = {}
        for a in ORB_ATTRS:
            b = tk.Button(af, text=a, width=4, font=("Segoe UI", 10, "bold"),
                          bg="#2C3E50", fg=ORB_COLORS[a],
                          activebackground=ORB_COLORS[a], activeforeground="#FFF",
                          relief=tk.FLAT, bd=0, pady=4,
                          command=lambda x=a: self._select_attr(x))
            b.pack(side=tk.LEFT, padx=2)
            self._attr_btns[a] = b

        wr = tk.Frame(f, bg="#243342"); wr.pack(fill=tk.X, pady=2)
        tk.Label(wr, text="優先倍率", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=4)
        self._var("priority_weight", tk.DoubleVar, 3.0)
        tk.Scale(wr, variable=self._vars["priority_weight"],
                 from_=1.0, to=5.0, resolution=0.5, orient=tk.HORIZONTAL,
                 bg="#243342", fg="#ECF0F1", troughcolor="#1E2933",
                 highlightthickness=0).pack(side=tk.LEFT, fill=tk.X, expand=True, padx=4)
        self._wt_lbl = tk.Label(wr, text="3.0×", fg="#F4D03F",
                                 bg="#243342", font=("Segoe UI", 9, "bold"), width=4)
        self._wt_lbl.pack(side=tk.LEFT)
        self._vars["priority_weight"].trace_add("write",
            lambda *_: self._wt_lbl.config(
                text=f"{self._vars['priority_weight'].get():.1f}×"))

        mr = tk.Frame(f, bg="#243342"); mr.pack(fill=tk.X, pady=2)
        tk.Label(mr, text="模型輸出", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=4)
        self._model_lbl = tk.Label(mr, text=ORB_MODEL["通用"],
                                    fg="#5DADE2", bg="#243342", font=("Consolas", 9))
        self._model_lbl.pack(side=tk.LEFT, padx=4)
        self._select_attr("通用")

    def _select_attr(self, attr):
        self._vars["priority"].set(attr)
        self._model_lbl.config(text=ORB_MODEL[attr])
        for a, b in self._attr_btns.items():
            b.config(bg=ORB_COLORS[a] if a == attr else "#2C3E50",
                     fg="white"        if a == attr else ORB_COLORS[a],
                     relief=tk.SUNKEN  if a == attr else tk.FLAT)

    def _section_data(self, parent):
        f = self._section(parent, "📊 生成訓練資料")
        self._int_row(f, "盤面數量",  "num_boards", 20000)
        self._int_row(f, "最大步數",  "max_steps",  35)
        self._int_row(f, "Beam寬度",  "beam_width", 45)
        self._int_row(f, "隨機種子",  "seed",       42)
        self._str_row(f, "輸出檔名",  "data_file",  "il_data.npz")

    def _section_il(self, parent):
        f = self._section(parent, "🧠 IL 模仿學習")
        self._int_row(f,   "訓練輪數",  "il_epochs",   30)
        self._int_row(f,   "批次大小",  "il_batch",    512)
        self._float_row(f, "學習率",    "il_lr",       0.001)
        self._float_row(f, "驗證比例",  "il_val_split",0.1)
        self._str_row(f,   "輸出檔名",  "il_ckpt",     "il_checkpoint.pt")

    def _section_rl(self, parent):
        f = self._section(parent, "🔬 RL 強化學習（選用）")
        self._int_row(f, "總訓練步數", "rl_total_steps", 1_000_000)
        self._int_row(f, "平行環境數", "rl_n_envs",      8)
        self._str_row(f, "輸出檔名",   "rl_ckpt",        "rl_checkpoint.pt")

    def _section_export(self, parent):
        f = self._section(parent, "📦 匯出 TFLite")
        self._combo_row(f, "訓練裝置", "device", ["cuda", "cpu", "mps"], "cuda")
        self._var("quantize", tk.BooleanVar, False)
        cr = tk.Frame(f, bg="#243342"); cr.pack(fill=tk.X, pady=2)
        tk.Checkbutton(cr, text="INT8 量化", variable=self._vars["quantize"],
                       bg="#243342", fg="#BDC3C7", selectcolor="#1E2933",
                       activebackground="#243342", font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=8)

    def _section_presets(self, parent):
        f = self._section(parent, "💾 預設方案")
        row = tk.Frame(f, bg="#243342"); row.pack(fill=tk.X, pady=4)
        self._var("preset_name", tk.StringVar, "我的預設")
        self.preset_combo = ttk.Combobox(row, textvariable=self._vars["preset_name"],
                                          font=("Segoe UI", 9), width=16)
        self.preset_combo.pack(side=tk.LEFT, padx=4)
        self._btn(row, "儲存", self._save_preset,   "#1A8754").pack(side=tk.LEFT, padx=2)
        self._btn(row, "載入", self._load_preset,   "#2980B9").pack(side=tk.LEFT, padx=2)
        self._btn(row, "刪除", self._delete_preset, "#C0392B").pack(side=tk.LEFT, padx=2)

    # ── Console & process ─────────────────────────────────────────────────────

    def _log(self, text, tag=""):
        self.console.config(state=tk.NORMAL)
        self.console.insert(tk.END, text, tag)
        self.console.see(tk.END)
        self.console.config(state=tk.DISABLED)

    def _clear_console(self):
        self.console.config(state=tk.NORMAL)
        self.console.delete("1.0", tk.END)
        self.console.config(state=tk.DISABLED)

    def _run_script(self, script, extra_args, step_name):
        if self._proc and self._proc.poll() is None:
            messagebox.showwarning("忙碌中", "請先等待目前的任務完成或按「停止」")
            return
        self._clear_console()
        self._log(f"▶ {step_name}\n", "info")
        self.status_lbl.config(text=step_name)
        self.progress.start(10)
        cmd = [sys.executable, os.path.join(SCRIPT_DIR, script)] + extra_args
        self._log(f"$ {' '.join(cmd)}\n\n", "info")

        def _reader():
            try:
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True,
                                        encoding="utf-8", errors="replace",
                                        cwd=SCRIPT_DIR, bufsize=1)
                self._proc = proc
                for line in proc.stdout:
                    self._queue.put(("line", line))
                proc.wait()
                self._queue.put(("done", proc.returncode))
            except Exception as e:
                self._queue.put(("err", str(e)))

        threading.Thread(target=_reader, daemon=True).start()

    def _poll_output(self):
        try:
            while True:
                t, d = self._queue.get_nowait()
                if t == "line":
                    tag = "err" if any(k in d for k in ("Error","error","Traceback")) else ""
                    tag = "ok"  if any(k in d for k in ("✓","saved","Done","complete","完成")) else tag
                    self._log(d, tag)
                elif t == "done":
                    self.progress.stop()
                    if d == 0:
                        self._log("\n✅ 完成！\n", "ok")
                        self.status_lbl.config(text="完成 ✓", fg="#2ECC71")
                        self._refresh_model_list()
                    else:
                        self._log(f"\n❌ 失敗 (code {d})\n", "err")
                        self.status_lbl.config(text="失敗 ✗", fg="#E74C3C")
                elif t == "err":
                    self.progress.stop()
                    self._log(f"\n❌ {d}\n", "err")
        except queue.Empty:
            pass
        self.after(100, self._poll_output)

    def _stop(self):
        if self._proc and self._proc.poll() is None:
            self._proc.terminate()
            self._log("\n⏹ 已停止\n", "err")
            self.progress.stop()
            self.status_lbl.config(text="已停止", fg="#E74C3C")

    # ── Train actions ─────────────────────────────────────────────────────────

    def _common_args(self):
        p = self._vars["priority"].get()
        return {"priority_type": p, "priority_weight": self._vars["priority_weight"].get()}

    def _run_generate(self):
        ca = self._common_args(); attr = ca["priority_type"]
        suffix = "" if attr == "通用" else f"_{attr}"
        fn = self._vars["data_file"].get().replace(".npz", f"{suffix}.npz")
        self._vars["data_file"].set(fn)
        self._run_script("generate_data.py", [
            "--num_boards",      str(self._vars["num_boards"].get()),
            "--max_steps",       str(self._vars["max_steps"].get()),
            "--beam_width",      str(self._vars["beam_width"].get()),
            "--seed",            str(self._vars["seed"].get()),
            "--output",          fn,
            "--priority_type",   attr,
            "--priority_weight", str(ca["priority_weight"]),
        ], f"生成訓練資料 [{attr}]")

    def _run_il(self):
        ca = self._common_args(); attr = ca["priority_type"]
        suffix = "" if attr == "通用" else f"_{attr}"
        il_fn = self._vars["il_ckpt"].get().replace(".pt", f"{suffix}.pt")
        self._vars["il_ckpt"].set(il_fn)
        self._run_script("train_il.py", [
            "--data",       self._vars["data_file"].get(),
            "--epochs",     str(self._vars["il_epochs"].get()),
            "--batch_size", str(self._vars["il_batch"].get()),
            "--lr",         str(self._vars["il_lr"].get()),
            "--val_split",  str(self._vars["il_val_split"].get()),
            "--output",     il_fn,
            "--device",     self._vars["device"].get(),
        ], f"IL 訓練 [{attr}]")

    def _run_rl(self):
        ca = self._common_args(); attr = ca["priority_type"]
        suffix = "" if attr == "通用" else f"_{attr}"
        rl_fn = self._vars["rl_ckpt"].get().replace(".pt", f"{suffix}.pt")
        self._vars["rl_ckpt"].set(rl_fn)
        self._run_script("train_rl.py", [
            "--il_checkpoint",   self._vars["il_ckpt"].get(),
            "--total_steps",     str(self._vars["rl_total_steps"].get()),
            "--n_envs",          str(self._vars["rl_n_envs"].get()),
            "--max_steps",       str(self._vars["max_steps"].get()),
            "--output",          rl_fn,
            "--device",          self._vars["device"].get(),
            "--priority_type",   attr,
            "--priority_weight", str(ca["priority_weight"]),
        ], f"RL 訓練 [{attr}]")

    def _run_export(self):
        ca = self._common_args(); attr = ca["priority_type"]
        rl_fn = self._vars["rl_ckpt"].get(); il_fn = self._vars["il_ckpt"].get()
        ckpt  = rl_fn if os.path.exists(os.path.join(SCRIPT_DIR, rl_fn)) else il_fn
        out   = os.path.join(ASSETS_DIR, ORB_MODEL[attr])
        os.makedirs(ASSETS_DIR, exist_ok=True)
        args  = ["--checkpoint", ckpt, "--output", out,
                 "--onnx", ckpt.replace(".pt", ".onnx")]
        if self._vars["quantize"].get(): args.append("--quantize")
        if ckpt == rl_fn: args.append("--rl")
        self._run_script("export_tflite.py", args, f"匯出 TFLite [{attr}]")

    def _run_all(self):
        if self._proc and self._proc.poll() is None:
            messagebox.showwarning("忙碌中", "請先等待"); return
        ca = self._common_args(); attr = ca["priority_type"]
        self._clear_console(); self._log(f"⚡ 一鍵全流程 [{attr}]\n\n", "info")
        suffix  = "" if attr == "通用" else f"_{attr}"
        data_fn = f"il_data{suffix}.npz"; il_fn = f"il_checkpoint{suffix}.pt"
        self._vars["data_file"].set(data_fn); self._vars["il_ckpt"].set(il_fn)
        out = os.path.join(ASSETS_DIR, ORB_MODEL[attr]); os.makedirs(ASSETS_DIR, exist_ok=True)
        steps = [
            ("generate_data.py", ["--num_boards", str(self._vars["num_boards"].get()),
                                  "--max_steps",  str(self._vars["max_steps"].get()),
                                  "--beam_width", str(self._vars["beam_width"].get()),
                                  "--seed",       str(self._vars["seed"].get()),
                                  "--output",     data_fn,
                                  "--priority_type",   attr,
                                  "--priority_weight", str(ca["priority_weight"])]),
            ("train_il.py",      ["--data", data_fn, "--epochs", str(self._vars["il_epochs"].get()),
                                  "--batch_size", str(self._vars["il_batch"].get()),
                                  "--lr",         str(self._vars["il_lr"].get()),
                                  "--val_split",  str(self._vars["il_val_split"].get()),
                                  "--output",     il_fn, "--device", self._vars["device"].get()]),
            ("export_tflite.py", ["--checkpoint", il_fn, "--output", out,
                                  "--onnx", il_fn.replace(".pt",".onnx")] +
                                 (["--quantize"] if self._vars["quantize"].get() else [])),
        ]
        self._chain_steps(steps, 0)

    def _chain_steps(self, steps, idx):
        if idx >= len(steps):
            self._log("\n✅ 全流程完成！\n", "ok")
            self.status_lbl.config(text="完成 ✓", fg="#2ECC71")
            self.progress.stop(); self._refresh_model_list(); return
        script, args = steps[idx]; label = f"[{idx+1}/{len(steps)}] {script}"
        self._log(f"\n{'─'*38}\n{label}\n{'─'*38}\n", "info")
        self.status_lbl.config(text=label); self.progress.start(10)
        cmd = [sys.executable, os.path.join(SCRIPT_DIR, script)] + args

        def _reader():
            try:
                proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, text=True,
                                        encoding="utf-8", errors="replace",
                                        cwd=SCRIPT_DIR, bufsize=1)
                self._proc = proc
                for line in proc.stdout: self._queue.put(("line", line))
                proc.wait()
                self._queue.put(("chain_next" if proc.returncode == 0 else "done",
                                 (steps, idx+1) if proc.returncode == 0 else proc.returncode))
            except Exception as e:
                self._queue.put(("err", str(e)))

        def _poll():
            try:
                while True:
                    t, d = self._queue.get_nowait()
                    if t == "chain_next":
                        self.progress.stop(); self._chain_steps(*d); return
                    elif t == "line":
                        tag = "err" if any(k in d for k in ("Error","error","Traceback")) else ""
                        tag = "ok"  if any(k in d for k in ("✓","saved","Done","complete")) else tag
                        self._log(d, tag)
                    elif t in ("done", "err"):
                        self.progress.stop()
                        self._log(f"\n❌ 失敗\n", "err"); return
            except queue.Empty:
                pass
            self.after(100, _poll)

        self.after(100, _poll)
        threading.Thread(target=_reader, daemon=True).start()

    # ── Model list ────────────────────────────────────────────────────────────

    def _refresh_model_list(self):
        self.model_list.delete(0, tk.END)
        if not os.path.isdir(ASSETS_DIR):
            self.model_list.insert(tk.END, "(assets/ 不存在)"); return
        files = [f for f in os.listdir(ASSETS_DIR) if f.endswith(".tflite")]
        if not files: self.model_list.insert(tk.END, "(尚無模型)")
        for f in sorted(files):
            kb = os.path.getsize(os.path.join(ASSETS_DIR, f)) / 1024
            self.model_list.insert(tk.END, f"✓  {f}  ({kb:.0f} KB)")

    def _open_assets_dir(self):
        os.makedirs(ASSETS_DIR, exist_ok=True)
        os.startfile(ASSETS_DIR)

    # ── Presets ───────────────────────────────────────────────────────────────

    def _read_presets(self):
        return json.load(open(PRESETS_FILE, encoding="utf-8")) if os.path.exists(PRESETS_FILE) else {}

    def _write_presets(self, p):
        json.dump(p, open(PRESETS_FILE, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    def _load_presets_list(self):
        self.preset_combo["values"] = list(self._read_presets().keys())

    def _save_preset(self):
        name = self._vars["preset_name"].get().strip()
        if not name: messagebox.showerror("錯誤","請輸入名稱"); return
        p = self._read_presets()
        p[name] = {k: v.get() for k, v in self._vars.items() if k != "preset_name"}
        self._write_presets(p); self._load_presets_list()
        self._log(f"✓ 預設「{name}」已儲存\n", "ok")

    def _load_preset(self):
        name = self._vars["preset_name"].get(); p = self._read_presets()
        if name not in p: messagebox.showerror("錯誤", f"找不到「{name}」"); return
        for k, v in p[name].items():
            if k in self._vars:
                try: self._vars[k].set(v)
                except: pass
        attr = self._vars.get("priority", tk.StringVar()).get()
        if attr in self._attr_btns: self._select_attr(attr)
        self._log(f"✓ 已載入「{name}」\n", "ok")

    def _delete_preset(self):
        name = self._vars["preset_name"].get(); p = self._read_presets()
        if name in p:
            del p[name]; self._write_presets(p); self._load_presets_list()
            self._log(f"✓ 已刪除「{name}」\n", "ok")


# ═══════════════════════════════════════════════════════════════════════════════
#  Tab 2 — Board Simulator
# ═══════════════════════════════════════════════════════════════════════════════

ROWS, COLS = 5, 6
CELL = 58   # cell size in pixels
GAP  = 3    # gap between cells


class BoardSimulatorTab:
    """Interactive 5×6 board editor with beam-search preview and training export."""

    def __init__(self, parent: tk.Frame, gui: TrainGUI):
        self.parent = parent
        self.gui    = gui

        # Board state: 2D list of orb-type strings ("水"…"?")
        self.board: list[list[str]] = [["?" for _ in range(COLS)] for _ in range(ROWS)]
        self.brush: str = "水"           # currently selected brush
        self.preview_path: list[tuple[int,int]] = []  # beam search result
        self.preview_combo: int = 0
        self.training_items: list[dict] = []  # items added for export
        self._mode: str = "paint"               # "paint" | "swipe"
        self._swipe_path: list[tuple[int,int]] = []
        self._swipe_active: bool = False

        # Playback / elimination animation
        self._path_origin_board: list[list[str]] = []
        self._play_state: str = "idle"          # "idle" | "playing" | "eliminating"
        self._play_step: int = 0
        self._play_after_id = None
        self._elim_board: list[list[str]] = []
        self._elim_match_mask: list[list[bool]] = [[False]*COLS for _ in range(ROWS)]
        self._elim_wave: int = 0
        self._elim_total_combos: int = 0

        # Pattern library
        self._library: list[dict] = []
        self._library_path: str = os.path.join(SCRIPT_DIR, "pattern_library.json")
        self._load_library()

        self._build()
        self._randomize_board()

    # ── Build layout ──────────────────────────────────────────────────────────

    def _build(self):
        body = tk.Frame(self.parent, bg="#1E2933")
        body.pack(fill=tk.BOTH, expand=True, padx=6, pady=6)

        # Left: board canvas + brush
        left = tk.Frame(body, bg="#1E2933")
        left.pack(side=tk.LEFT, fill=tk.Y, padx=(0, 8))
        self._build_board_area(left)

        # Right: target config + preview info + training list
        right = tk.Frame(body, bg="#1E2933")
        right.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        self._build_right_panel(right)

    def _build_board_area(self, parent):
        tk.Label(parent, text="盤面編輯器", fg="#ECF0F1", bg="#1E2933",
                 font=("Segoe UI", 11, "bold")).pack(pady=(0, 4))

        # Canvas
        cw = COLS * (CELL + GAP) + GAP
        ch = ROWS * (CELL + GAP) + GAP
        self.canvas = tk.Canvas(parent, width=cw, height=ch,
                                bg="#151E27", highlightthickness=1,
                                highlightbackground="#2980B9")
        self.canvas.pack()
        self.canvas.bind("<Button-1>",         self._on_left_click)
        self.canvas.bind("<B1-Motion>",        self._on_drag)
        self.canvas.bind("<ButtonRelease-1>",  self._on_swipe_end)
        self.canvas.bind("<Button-3>",         self._on_right_click)

        # Brush selector
        tk.Label(parent, text="筆刷屬性：", fg="#BDC3C7", bg="#1E2933",
                 font=("Segoe UI", 9)).pack(pady=(8, 2))
        br = tk.Frame(parent, bg="#1E2933")
        br.pack()
        self._brush_btns: dict[str, tk.Button] = {}
        for t in CELL_TYPES:
            if t == "?": continue
            b = tk.Button(br, text=t, width=3, font=("Segoe UI", 10, "bold"),
                          bg=CELL_COLORS[t], fg=CELL_TEXT_COLORS[t],
                          activebackground=CELL_TEXT_COLORS[t],
                          relief=tk.FLAT, bd=2, pady=4,
                          command=lambda x=t: self._set_brush(x))
            b.pack(side=tk.LEFT, padx=1)
            self._brush_btns[t] = b
        self._set_brush("水")

        # Board action buttons
        act = tk.Frame(parent, bg="#1E2933")
        act.pack(pady=6)
        self._sbtn(act, "🎲 隨機",   self._randomize_board, "#2980B9").pack(side=tk.LEFT, padx=3)
        self._sbtn(act, "🗑 清空",   self._clear_board,     "#7F8C8D").pack(side=tk.LEFT, padx=3)
        self._sbtn(act, "↩ 復原",   self._undo,            "#6C3483").pack(side=tk.LEFT, padx=3)
        self._mode_btn = self._sbtn(act, "✋ 滑動模式", self._toggle_mode, "#16A085")
        self._mode_btn.pack(side=tk.LEFT, padx=3)

        # Legend
        leg = tk.Frame(parent, bg="#1E2933"); leg.pack(pady=2)
        self._legend_lbl = tk.Label(leg, text="左鍵塗色  右鍵清除",
                 fg="#636E72", bg="#1E2933", font=("Segoe UI", 8))
        self._legend_lbl.pack()

    def _build_right_panel(self, parent):
        # ── Target config ──
        tgt = tk.LabelFrame(parent, text=" 🎯 訓練目標設定 ", bg="#243342",
                            fg="#ECF0F1", font=("Segoe UI", 9, "bold"), padx=8, pady=6)
        tgt.pack(fill=tk.X, pady=(0, 6))

        # Target type
        tr = tk.Frame(tgt, bg="#243342"); tr.pack(fill=tk.X, pady=2)
        tk.Label(tr, text="目標類型", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9), width=8).pack(side=tk.LEFT)
        self._tgt_var = tk.StringVar(value="最高Combo")
        self._tgt_cb  = ttk.Combobox(tr, textvariable=self._tgt_var,
                                      values=TARGET_TYPES, state="readonly", width=14)
        self._tgt_cb.pack(side=tk.LEFT, padx=4)
        self._tgt_var.trace_add("write", lambda *_: self._on_target_change())

        # Attribute sub-option (shown for shape targets)
        self._attr_row = tk.Frame(tgt, bg="#243342")
        self._attr_row.pack(fill=tk.X, pady=2)
        tk.Label(self._attr_row, text="形狀屬性", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9), width=8).pack(side=tk.LEFT)
        self._shape_attr_var = tk.StringVar(value="水")
        self._shape_attr_cb  = ttk.Combobox(
            self._attr_row, textvariable=self._shape_attr_var,
            values=[a for a in ORB_ATTRS if a != "通用"], state="readonly", width=6)
        self._shape_attr_cb.pack(side=tk.LEFT, padx=4)

        # Count sub-option (shown for 指定Combo數)
        self._count_row = tk.Frame(tgt, bg="#243342")
        self._count_row.pack(fill=tk.X, pady=2)
        tk.Label(self._count_row, text="目標Combo", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9), width=8).pack(side=tk.LEFT)
        self._target_n_var = tk.IntVar(value=5)
        tk.Spinbox(self._count_row, textvariable=self._target_n_var,
                   from_=1, to=20, width=6,
                   bg="#1E2933", fg="#ECF0F1", insertbackground="#ECF0F1",
                   buttonbackground="#243342", relief=tk.FLAT).pack(side=tk.LEFT, padx=4)

        # Beam search params (max steps)
        sr = tk.Frame(tgt, bg="#243342"); sr.pack(fill=tk.X, pady=2)
        tk.Label(sr, text="最大步數", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9), width=8).pack(side=tk.LEFT)
        self._sim_steps_var = tk.IntVar(value=35)
        tk.Spinbox(sr, textvariable=self._sim_steps_var,
                   from_=5, to=60, width=6,
                   bg="#1E2933", fg="#ECF0F1", insertbackground="#ECF0F1",
                   buttonbackground="#243342", relief=tk.FLAT).pack(side=tk.LEFT, padx=4)

        self._on_target_change()  # set initial visibility

        # Preview button + result
        pr = tk.Frame(tgt, bg="#243342"); pr.pack(fill=tk.X, pady=6)
        self._sbtn(pr, "🔍 預覽最佳路徑", self._run_preview, "#2980B9").pack(side=tk.LEFT, padx=4)
        self._preview_lbl = tk.Label(pr, text="", fg="#2ECC71", bg="#243342",
                                      font=("Segoe UI", 9, "bold"))
        self._preview_lbl.pack(side=tk.LEFT, padx=8)

        # Playback controls
        pr2 = tk.Frame(tgt, bg="#243342"); pr2.pack(fill=tk.X, pady=(0, 4))
        self._play_btn = self._sbtn(pr2, "▶ 播放路徑", self._start_playback, "#27AE60")
        self._play_btn.pack(side=tk.LEFT, padx=4)
        self._elim_btn = self._sbtn(pr2, "💥 消除模擬", self._start_elimination_from_current, "#C0392B")
        self._elim_btn.pack(side=tk.LEFT, padx=4)
        tk.Label(pr2, text="速度(ms):", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=(8, 2))
        self._play_speed_var = tk.IntVar(value=150)
        tk.Spinbox(pr2, textvariable=self._play_speed_var, from_=30, to=800,
                   width=5, bg="#1E2933", fg="#ECF0F1", insertbackground="#ECF0F1",
                   buttonbackground="#243342", relief=tk.FLAT).pack(side=tk.LEFT)

        # Add to training
        ar = tk.Frame(tgt, bg="#243342"); ar.pack(fill=tk.X, pady=2)
        self._sbtn(ar, "➕ 加入訓練目標", self._add_to_training, "#1A8754").pack(side=tk.LEFT, padx=4)
        self._add_lbl = tk.Label(ar, text="", fg="#F4D03F", bg="#243342",
                                  font=("Segoe UI", 9))
        self._add_lbl.pack(side=tk.LEFT, padx=4)

        # ── Training items list ──
        lst = tk.LabelFrame(parent, text=" 📋 已加入的訓練目標 ", bg="#243342",
                            fg="#ECF0F1", font=("Segoe UI", 9, "bold"), padx=8, pady=6)
        lst.pack(fill=tk.BOTH, expand=True)

        self._item_listbox = tk.Listbox(lst, bg="#0D1117", fg="#B0C4DE",
                                         font=("Consolas", 9), height=8,
                                         selectmode=tk.BROWSE)
        sb = ttk.Scrollbar(lst, orient=tk.VERTICAL, command=self._item_listbox.yview)
        self._item_listbox.configure(yscrollcommand=sb.set)
        sb.pack(side=tk.RIGHT, fill=tk.Y)
        self._item_listbox.pack(fill=tk.BOTH, expand=True)
        self._item_listbox.bind("<Double-Button-1>", self._load_selected_item)

        lr = tk.Frame(lst, bg="#243342"); lr.pack(fill=tk.X, pady=4)
        self._items_lbl = tk.Label(lr, text="共 0 筆", fg="#BDC3C7",
                                    bg="#243342", font=("Segoe UI", 9))
        self._items_lbl.pack(side=tk.LEFT, padx=4)
        self._sbtn(lr, "📦 匯出 NPZ", self._export_data,    "#E67E22").pack(side=tk.RIGHT, padx=3)
        self._sbtn(lr, "🗑 清除選中", self._remove_selected, "#C0392B").pack(side=tk.RIGHT, padx=3)
        self._sbtn(lr, "🗑 清除全部", self._clear_items,     "#7F8C8D").pack(side=tk.RIGHT, padx=3)

        self._build_library_panel(parent)

    @staticmethod
    def _sbtn(parent, text, cmd, color):
        return tk.Button(parent, text=text, command=cmd, bg=color, fg="white",
                         font=("Segoe UI", 9), relief=tk.FLAT, padx=6, pady=3,
                         activebackground=color, activeforeground="white")

    # ── Board interaction ─────────────────────────────────────────────────────

    def _canvas_to_cell(self, x, y):
        c = int(x // (CELL + GAP))
        r = int(y // (CELL + GAP))
        if 0 <= r < ROWS and 0 <= c < COLS:
            return r, c
        return None, None

    def _on_left_click(self, event):
        if self._mode == "swipe":
            self._on_swipe_start(event)
            return
        r, c = self._canvas_to_cell(event.x, event.y)
        if r is not None:
            self._history_push()
            self.board[r][c] = self.brush
            self._draw_board()

    def _on_drag(self, event):
        if self._mode == "swipe":
            self._on_swipe_move(event)
            return
        r, c = self._canvas_to_cell(event.x, event.y)
        if r is not None and self.board[r][c] != self.brush:
            self.board[r][c] = self.brush
            self._draw_board()

    def _on_right_click(self, event):
        r, c = self._canvas_to_cell(event.x, event.y)
        if r is not None:
            self._history_push()
            self.board[r][c] = "?"
            self._draw_board()

    def _toggle_mode(self):
        if self._mode == "paint":
            self._mode = "swipe"
            self._mode_btn.config(text="🖌 塗色模式", bg="#8E44AD")
            self._legend_lbl.config(text="左鍵拖曳滑動  右鍵清除")
        else:
            self._mode = "paint"
            self._swipe_active = False
            self._swipe_path = []
            self._mode_btn.config(text="✋ 滑動模式", bg="#16A085")
            self._legend_lbl.config(text="左鍵塗色  右鍵清除")
            self._draw_board()

    def _on_swipe_start(self, event):
        r, c = self._canvas_to_cell(event.x, event.y)
        if r is not None and self.board[r][c] != "?":
            self._history_push()
            self._swipe_active = True
            self._swipe_path = [(r, c)]
            self._path_origin_board = [row[:] for row in self.board]
            self.preview_path = []
            self._draw_board()

    def _on_swipe_move(self, event):
        if not self._swipe_active or not self._swipe_path:
            return
        r, c = self._canvas_to_cell(event.x, event.y)
        if r is None:
            return
        last_r, last_c = self._swipe_path[-1]
        if (r, c) == (last_r, last_c):
            return
        # Going back to previous cell: undo last swap
        if len(self._swipe_path) >= 2 and (r, c) == self._swipe_path[-2]:
            self.board[last_r][last_c], self.board[r][c] = \
                self.board[r][c], self.board[last_r][last_c]
            self._swipe_path.pop()
            self._draw_board()
            return
        # Move to new cell: swap held orb with destination
        self.board[last_r][last_c], self.board[r][c] = \
            self.board[r][c], self.board[last_r][last_c]
        self._swipe_path.append((r, c))
        self._draw_board()

    def _on_swipe_end(self, event):
        if not self._swipe_active or self._mode != "swipe":
            return
        self._swipe_active = False
        if len(self._swipe_path) > 1:
            self.preview_path = list(self._swipe_path)
            # Compute combos from final board state
            try:
                from board_simulator import count_combos
                import numpy as np
                board_np = self._board_to_numpy()
                self.preview_combo = count_combos(board_np)
            except Exception:
                self.preview_combo = 0
            self._preview_lbl.config(
                text=f"Combo: {self.preview_combo}  步數: {len(self.preview_path)}",
                fg="#2ECC71" if self.preview_combo > 0 else "#E74C3C")
        self._swipe_path = []
        self._draw_board()

    # ── Playback animation ────────────────────────────────────────────────────

    def _stop_playback(self):
        if self._play_after_id is not None:
            self.parent.after_cancel(self._play_after_id)
            self._play_after_id = None
        self._play_state = "idle"
        self._play_btn.config(text="▶ 播放路徑", bg="#27AE60")
        self._elim_btn.config(text="💥 消除模擬", bg="#C0392B")

    def _start_playback(self):
        if self._play_state != "idle":
            self._stop_playback()
            self._draw_board()
            return
        if not self.preview_path or not self._path_origin_board:
            return
        self._play_state = "playing"
        self._play_step = 0
        self.board = [row[:] for row in self._path_origin_board]
        self._play_btn.config(text="⏹ 停止", bg="#E67E22")
        self._draw_board()
        self._play_after_id = self.parent.after(
            self._play_speed_var.get(), self._playback_next_step)

    def _playback_next_step(self):
        self._play_after_id = None
        if self._play_state != "playing":
            return
        if self._play_step >= len(self.preview_path) - 1:
            # Playback done — restore play button, then auto-start elimination
            self._play_state = "idle"
            self._play_btn.config(text="▶ 播放路徑", bg="#27AE60")
            self._draw_board()
            self._start_elimination_from_current()
            return
        r1, c1 = self.preview_path[self._play_step]
        r2, c2 = self.preview_path[self._play_step + 1]
        self.board[r1][c1], self.board[r2][c2] = self.board[r2][c2], self.board[r1][c1]
        self._play_step += 1
        self._draw_board()
        # Highlight held orb
        x0 = GAP + c2 * (CELL + GAP)
        y0 = GAP + r2 * (CELL + GAP)
        self.canvas.create_rectangle(x0+1, y0+1, x0+CELL-1, y0+CELL-1,
                                     outline="white", width=3)
        self._play_after_id = self.parent.after(
            self._play_speed_var.get(), self._playback_next_step)

    # ── Elimination simulation ─────────────────────────────────────────────────

    def _start_elimination_from_current(self):
        if self._play_state != "idle":
            self._stop_playback()
            self._draw_board()
            return
        self._play_state = "eliminating"
        self._elim_board = [row[:] for row in self.board]
        self._elim_wave = 0
        self._elim_total_combos = 0
        self._elim_match_mask = [[False] * COLS for _ in range(ROWS)]
        self._elim_btn.config(text="⏹ 停止", bg="#E67E22")
        self._elimination_find_matches()

    def _elimination_find_matches(self):
        if self._play_state != "eliminating":
            return
        try:
            from board_simulator import _build_match_map, count_combos
        except ImportError:
            self._stop_playback()
            return
        arr = self._board_list_to_numpy(self._elim_board)
        match_map = _build_match_map(arr)
        if not match_map.any():
            # No more matches — commit board and show result
            self.board = [row[:] for row in self._elim_board]
            self._play_state = "idle"
            self._elim_btn.config(text="💥 消除模擬", bg="#C0392B")
            msg = (f"消除完成  波數:{self._elim_wave}  "
                   f"累計Combo:{self._elim_total_combos}")
            self._preview_lbl.config(
                text=msg,
                fg="#2ECC71" if self._elim_total_combos > 0 else "#BDC3C7")
            self._draw_board()
            return
        wave_combos = count_combos(arr)
        self._elim_wave += 1
        self._elim_total_combos += wave_combos
        self._elim_match_mask = match_map.tolist()
        self._draw_elim_board()
        self._play_after_id = self.parent.after(
            self._play_speed_var.get(), self._elimination_do_clear)

    def _elimination_do_clear(self):
        self._play_after_id = None
        if self._play_state != "eliminating":
            return
        for r in range(ROWS):
            for c in range(COLS):
                if self._elim_match_mask[r][c]:
                    self._elim_board[r][c] = "?"
        self._elim_match_mask = [[False] * COLS for _ in range(ROWS)]
        self._apply_gravity_to_board(self._elim_board)
        self._draw_elim_board()
        self._play_after_id = self.parent.after(
            self._play_speed_var.get(), self._elimination_find_matches)

    def _apply_gravity_to_board(self, board):
        """Drop non-empty orbs to the bottom of each column."""
        for c in range(COLS):
            col = [board[r][c] for r in range(ROWS) if board[r][c] != "?"]
            pad = ROWS - len(col)
            new_col = ["?"] * pad + col
            for r in range(ROWS):
                board[r][c] = new_col[r]

    def _draw_elim_board(self):
        """Draw the elimination board with matched cells highlighted in gold."""
        self.canvas.delete("all")
        for r in range(ROWS):
            for c in range(COLS):
                x0 = GAP + c * (CELL + GAP)
                y0 = GAP + r * (CELL + GAP)
                x1, y1 = x0 + CELL, y0 + CELL
                t = self._elim_board[r][c]
                matched = self._elim_match_mask[r][c]
                bg = "#F1C40F" if matched else CELL_COLORS.get(t, "#2C3E50")
                fg = "#1E2933" if matched else CELL_TEXT_COLORS.get(t, "#888888")

                self.canvas.create_rectangle(x0+4, y0, x1-4, y1, fill=bg, outline="")
                self.canvas.create_rectangle(x0, y0+4, x1, y1-4, fill=bg, outline="")
                self.canvas.create_oval(x0, y0, x0+8, y0+8, fill=bg, outline="")
                self.canvas.create_oval(x1-8, y0, x1, y0+8, fill=bg, outline="")
                self.canvas.create_oval(x0, y1-8, x0+8, y1, fill=bg, outline="")
                self.canvas.create_oval(x1-8, y1-8, x1, y1, fill=bg, outline="")

                if t != "?":
                    cx_c, cy_c = (x0+x1)//2, (y0+y1)//2
                    label = "💥" if matched else t
                    self.canvas.create_text(cx_c, cy_c, text=label, fill=fg,
                                            font=("Segoe UI", 20, "bold"))

    def _board_list_to_numpy(self, board):
        import numpy as np
        arr = [[ORB_INT.get(board[r][c], 6) for c in range(COLS)] for r in range(ROWS)]
        return np.array(arr, dtype=np.int8)

    def _set_brush(self, t: str):
        self.brush = t
        for typ, btn in self._brush_btns.items():
            if typ == t:
                btn.config(relief=tk.SUNKEN, bd=3)
            else:
                btn.config(relief=tk.FLAT,   bd=2)

    # ── Drawing ───────────────────────────────────────────────────────────────

    def _draw_board(self):
        self.canvas.delete("all")
        for r in range(ROWS):
            for c in range(COLS):
                x0 = GAP + c * (CELL + GAP)
                y0 = GAP + r * (CELL + GAP)
                x1, y1 = x0 + CELL, y0 + CELL
                t = self.board[r][c]
                bg = CELL_COLORS.get(t, "#2C3E50")
                fg = CELL_TEXT_COLORS.get(t, "#888888")

                # Rounded rect (simulate with oval overlap)
                self.canvas.create_rectangle(x0+4, y0, x1-4, y1, fill=bg, outline="")
                self.canvas.create_rectangle(x0, y0+4, x1, y1-4, fill=bg, outline="")
                self.canvas.create_oval(x0, y0, x0+8, y0+8, fill=bg, outline="")
                self.canvas.create_oval(x1-8, y0, x1, y0+8, fill=bg, outline="")
                self.canvas.create_oval(x0, y1-8, x0+8, y1, fill=bg, outline="")
                self.canvas.create_oval(x1-8, y1-8, x1, y1, fill=bg, outline="")

                if t != "?":
                    cx, cy = (x0+x1)//2, (y0+y1)//2
                    self.canvas.create_text(cx, cy, text=t, fill=fg,
                                            font=("Segoe UI", 20, "bold"))

        # Draw path overlay
        active_path = self._swipe_path if self._swipe_active else self.preview_path
        if active_path:
            self._draw_path(active_path)
        if self._swipe_active and self._swipe_path:
            r, c = self._swipe_path[-1]
            x0 = GAP + c * (CELL + GAP)
            y0 = GAP + r * (CELL + GAP)
            self.canvas.create_rectangle(x0+1, y0+1, x0+CELL-1, y0+CELL-1,
                                         outline="white", width=3)

    def _draw_path(self, path=None):
        path = path or self.preview_path
        n = len(path)
        for i, (r, c) in enumerate(path):
            x0 = GAP + c * (CELL + GAP)
            y0 = GAP + r * (CELL + GAP)
            cx, cy = x0 + CELL//2, y0 + CELL//2

            # Gradient: green → red
            frac = i / max(n - 1, 1)
            rv = int(min(frac * 2, 1.0) * 220)
            gv = int(min((1-frac) * 2, 1.0) * 220)
            color = f"#{rv:02X}{gv:02X}30"

            radius = 14 if i in (0, n-1) else 9
            self.canvas.create_oval(cx-radius, cy-radius, cx+radius, cy+radius,
                                    fill=color, outline="white", width=2)
            self.canvas.create_text(cx, cy, text=str(i+1),
                                    fill="white", font=("Segoe UI", 7, "bold"))

            # Arrow to next
            if i < n - 1:
                nr, nc = path[i+1]
                nx = GAP + nc*(CELL+GAP) + CELL//2
                ny = GAP + nr*(CELL+GAP) + CELL//2
                self.canvas.create_line(cx, cy, nx, ny,
                                        fill="white", width=2, arrow=tk.LAST,
                                        arrowshape=(8, 10, 4))

    # ── Board helpers ─────────────────────────────────────────────────────────

    def _board_to_numpy(self):
        import numpy as np
        arr = [[ORB_INT.get(self.board[r][c], 6) for c in range(COLS)]
               for r in range(ROWS)]
        return __import__("numpy").array(arr, dtype=__import__("numpy").int8)

    def _randomize_board(self):
        import random
        types = [t for t in CELL_TYPES if t != "?"]
        self._history_push()
        self.board = [[random.choice(types) for _ in range(COLS)] for _ in range(ROWS)]
        self.preview_path = []
        self._draw_board()

    def _clear_board(self):
        self._history_push()
        self.board = [["?" for _ in range(COLS)] for _ in range(ROWS)]
        self.preview_path = []
        self._draw_board()

    # ── Undo ──────────────────────────────────────────────────────────────────

    _history: list = []

    def _history_push(self):
        self._history.append([row[:] for row in self.board])
        if len(self._history) > 50:
            self._history.pop(0)

    def _undo(self):
        if self._history:
            self.board = self._history.pop()
            self.preview_path = []
            self._draw_board()

    # ── Target config visibility ──────────────────────────────────────────────

    def _on_target_change(self):
        tgt = self._tgt_var.get()
        if tgt in ATTR_TARGETS:
            self._attr_row.pack(fill=tk.X, pady=2)
        else:
            self._attr_row.pack_forget()
        if tgt in COUNT_TARGETS:
            self._count_row.pack(fill=tk.X, pady=2)
        else:
            self._count_row.pack_forget()

    # ── Beam search preview ───────────────────────────────────────────────────

    def _run_preview(self):
        self._preview_lbl.config(text="計算中…", fg="#F4D03F")
        board_np = self._board_to_numpy()

        # ── Check pattern library first ────────────────────────────────────────
        hint, is_exact = self._find_library_hint(board_np)
        if is_exact:
            import numpy as np
            entry = next(e for e in self._library
                         if np.array(e["board"], dtype=np.int8).tobytes() == board_np.tobytes())
            path   = [tuple(p) for p in entry["path"]]
            combos = entry["combo"]
            self.preview_path  = path
            self.preview_combo = combos
            self._path_origin_board = [row[:] for row in self.board]
            self._preview_lbl.config(
                text=f"[題庫] Combo:{combos}  步:{len(path)}",
                fg="#F4D03F")
            self._draw_board()
            return

        tgt      = self._tgt_var.get()
        attr_lbl = self._shape_attr_var.get() if tgt in ATTR_TARGETS else None
        target_n = self._target_n_var.get()
        steps    = self._sim_steps_var.get()

        def _run():
            from board_simulator import beam_search, OrbType, ORB_LABELS
            attr_type = OrbType(ORB_INT[attr_lbl]) if attr_lbl else None
            path, combos = beam_search(
                board_np, max_steps=steps, beam_width=45,
                priority_type=attr_type, target_type=tgt, target_n=target_n,
                hint_path=hint)
            return path, combos

        def _done(path, combos):
            self.preview_path  = path
            self.preview_combo = combos
            self._path_origin_board = [row[:] for row in self.board]
            self._preview_lbl.config(
                text=f"Combo: {combos}  步數: {len(path)}",
                fg="#2ECC71" if combos > 0 else "#E74C3C")
            self._draw_board()

        def _thread():
            try:
                path, combos = _run()
                self.parent.after(0, lambda: _done(path, combos))
            except Exception as e:
                self.parent.after(0, lambda: self._preview_lbl.config(
                    text=f"錯誤: {e}", fg="#E74C3C"))

        threading.Thread(target=_thread, daemon=True).start()

    # ── Training data management ──────────────────────────────────────────────

    def _add_to_training(self):
        if not self.preview_path:
            self._add_lbl.config(text="請先按「預覽最佳路徑」", fg="#E74C3C")
            return
        tgt      = self._tgt_var.get()
        attr_lbl = self._shape_attr_var.get() if tgt in ATTR_TARGETS else "通用"
        item = {
            "board":       [row[:] for row in self.board],
            "path":        list(self.preview_path),
            "combo":       self.preview_combo,
            "target_type": tgt,
            "attr":        attr_lbl,
            "target_n":    self._target_n_var.get(),
        }
        self.training_items.append(item)
        desc = f"#{len(self.training_items)}  {tgt}"
        if attr_lbl and attr_lbl != "通用":
            desc += f"[{attr_lbl}]"
        desc += f"  → {self.preview_combo}combo / {len(self.preview_path)}步"
        self._item_listbox.insert(tk.END, desc)
        self._items_lbl.config(text=f"共 {len(self.training_items)} 筆")
        self._add_lbl.config(text=f"已加入 #{len(self.training_items)}", fg="#2ECC71")

    def _load_selected_item(self, _event=None):
        sel = self._item_listbox.curselection()
        if not sel: return
        item = self.training_items[sel[0]]
        self._history_push()
        self.board        = [row[:] for row in item["board"]]
        self.preview_path = list(item["path"])
        self.preview_combo= item["combo"]
        self._tgt_var.set(item["target_type"])
        self._draw_board()

    def _remove_selected(self):
        sel = self._item_listbox.curselection()
        if not sel: return
        idx = sel[0]
        self.training_items.pop(idx)
        self._item_listbox.delete(idx)
        self._items_lbl.config(text=f"共 {len(self.training_items)} 筆")

    def _clear_items(self):
        self.training_items.clear()
        self._item_listbox.delete(0, tk.END)
        self._items_lbl.config(text="共 0 筆")

    def _export_data(self):
        if not self.training_items:
            messagebox.showinfo("提示", "請先加入訓練目標"); return

        from tkinter.filedialog import asksaveasfilename
        path = asksaveasfilename(
            defaultextension=".npz",
            filetypes=[("NumPy压缩档", "*.npz")],
            initialfile="sim_data.npz",
            initialdir=SCRIPT_DIR,
            title="儲存模擬器訓練資料")
        if not path: return

        # Convert items → (state, action) pairs using path_to_il_samples
        import numpy as np
        from board_simulator import OrbType, ORB_LABELS
        from generate_data import path_to_il_samples

        all_states, all_actions = [], []
        for item in self.training_items:
            board_np = __import__("numpy").array(
                [[ORB_INT.get(c, 6) for c in row] for row in item["board"]],
                dtype=__import__("numpy").int8)
            pairs = path_to_il_samples(board_np, item["path"])
            for s, a in pairs:
                all_states.append(s); all_actions.append(a)

        if not all_states:
            messagebox.showerror("錯誤", "沒有有效的訓練樣本"); return

        np.savez_compressed(path,
                            states=np.array(all_states,  dtype=np.float32),
                            actions=np.array(all_actions, dtype=np.int64))
        n = len(all_states)
        messagebox.showinfo("完成", f"已匯出 {n} 筆樣本到\n{path}\n\n"
                                    f"可與 il_data.npz 合併後一起訓練：\n"
                                    f"python merge_data.py il_data.npz {os.path.basename(path)}")
        self.gui._log(f"✓ 模擬器資料已匯出: {path} ({n} 筆)\n", "ok")

    # ── Pattern Library ───────────────────────────────────────────────────────

    def _build_library_panel(self, parent):
        lib = tk.LabelFrame(parent, text=" 📚 解法題庫 ", bg="#243342",
                            fg="#ECF0F1", font=("Segoe UI", 9, "bold"), padx=8, pady=6)
        lib.pack(fill=tk.X, pady=(6, 0))

        # Notes entry + add button
        nr = tk.Frame(lib, bg="#243342"); nr.pack(fill=tk.X, pady=2)
        tk.Label(nr, text="備註:", fg="#BDC3C7", bg="#243342",
                 font=("Segoe UI", 9)).pack(side=tk.LEFT, padx=4)
        self._lib_notes_var = tk.StringVar()
        tk.Entry(nr, textvariable=self._lib_notes_var, bg="#1E2933", fg="#ECF0F1",
                 insertbackground="#ECF0F1", font=("Consolas", 9),
                 relief=tk.FLAT, width=16).pack(side=tk.LEFT, padx=4)
        self._sbtn(nr, "📌 加入題庫", self._add_to_library, "#8E44AD").pack(side=tk.LEFT, padx=4)

        # Listbox
        self._lib_listbox = tk.Listbox(lib, bg="#0D1117", fg="#B0C4DE",
                                        font=("Consolas", 9), height=5,
                                        selectmode=tk.BROWSE)
        lsb = ttk.Scrollbar(lib, orient=tk.VERTICAL, command=self._lib_listbox.yview)
        self._lib_listbox.configure(yscrollcommand=lsb.set)
        lsb.pack(side=tk.RIGHT, fill=tk.Y)
        self._lib_listbox.pack(fill=tk.BOTH, expand=True, pady=2)

        # Bottom row
        br = tk.Frame(lib, bg="#243342"); br.pack(fill=tk.X, pady=2)
        self._sbtn(br, "🗑 移除", self._remove_from_library, "#C0392B").pack(side=tk.LEFT, padx=3)
        self._sbtn(br, "💾 匯出到 Android", self._export_library_to_android,
                   "#E67E22").pack(side=tk.LEFT, padx=3)

        self._refresh_library_listbox()

    def _load_library(self):
        if os.path.exists(self._library_path):
            try:
                with open(self._library_path, encoding="utf-8") as f:
                    data = json.load(f)
                self._library = data.get("patterns", [])
            except Exception:
                self._library = []
        else:
            self._library = []

    def _save_library(self):
        data = {"version": 1, "patterns": self._library}
        with open(self._library_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def _find_library_hint(self, board_np) -> tuple:
        """
        Returns (path, is_exact).
        is_exact=True  → exact board match found; path is the stored solution.
        is_exact=False → no match; path is None (beam search will run unaided).
        """
        import numpy as np
        board_bytes = board_np.tobytes()
        for entry in self._library:
            e_bytes = np.array(entry["board"], dtype=np.int8).tobytes()
            if e_bytes == board_bytes:
                return [tuple(p) for p in entry["path"]], True
        return None, False

    def _add_to_library(self):
        if not self.preview_path:
            messagebox.showwarning("提示", "請先執行「預覽最佳路徑」")
            return
        import numpy as np
        board_np  = self._board_to_numpy()
        board_list = board_np.tolist()
        notes = self._lib_notes_var.get().strip()
        new_id = max((e["id"] for e in self._library), default=0) + 1
        entry = {
            "id":    new_id,
            "board": board_list,
            "path":  [list(p) for p in self.preview_path],
            "combo": self.preview_combo,
            "notes": notes,
        }
        self._library.append(entry)
        self._save_library()
        self._refresh_library_listbox()
        self._lib_notes_var.set("")
        self.gui._log(f"✓ 加入題庫 #{new_id}  {self.preview_combo}combo  {notes}\n", "ok")

    def _remove_from_library(self):
        sel = self._lib_listbox.curselection()
        if not sel:
            return
        idx = sel[0]
        self._library.pop(idx)
        self._save_library()
        self._refresh_library_listbox()

    def _export_library_to_android(self):
        if not self._library:
            messagebox.showinfo("提示", "題庫是空的"); return
        import shutil
        os.makedirs(ASSETS_DIR, exist_ok=True)
        dst = os.path.join(ASSETS_DIR, "pattern_library.json")
        shutil.copy2(self._library_path, dst)
        messagebox.showinfo("完成", f"已匯出到\n{dst}")
        self.gui._log(f"✓ pattern_library.json 已匯出到 assets/\n", "ok")

    def _refresh_library_listbox(self):
        self._lib_listbox.delete(0, tk.END)
        for e in self._library:
            desc = f"#{e['id']}  {e['combo']}combo/{len(e['path'])}步"
            if e.get("notes"):
                desc += f"  {e['notes']}"
            self._lib_listbox.insert(tk.END, desc)


# ── Entry point ───────────────────────────────────────────────────────────────

if __name__ == "__main__":
    app = TrainGUI()
    app.mainloop()
