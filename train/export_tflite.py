"""
export_tflite.py
================
Converts a trained PyTorch OrbPolicy checkpoint to a TFLite flat-buffer that
can be loaded by MLOrbPlanner.kt on Android.

Conversion pipeline:
    PyTorch (.pt)  →  Keras (weight copy)  →  TFLite (.tflite)
    ONNX export is kept as an optional side-output for debugging.

Requirements:
    pip install torch tensorflow onnx

Usage:
    # From IL checkpoint
    python export_tflite.py --checkpoint il_checkpoint.pt --output orb_policy.tflite

    # From RL checkpoint (SB3 policy state_dict)
    python export_tflite.py --checkpoint rl_checkpoint.pt --output orb_policy.tflite --rl

After export, copy orb_policy.tflite to:
    app/src/main/assets/orb_policy.tflite
"""

from __future__ import annotations
import argparse
import os
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
import numpy as np
import torch
import onnx
from model import OrbPolicy, INPUT_SIZE, NUM_ACTIONS, HIDDEN1, HIDDEN2


# ── Step 1: Load PyTorch model ────────────────────────────────────────────────

def load_model(checkpoint: str, is_rl: bool) -> OrbPolicy:
    model = OrbPolicy()
    state = torch.load(checkpoint, map_location="cpu")

    if is_rl:
        # SB3 saves policy.state_dict() which has different key names
        # Map SB3 MlpPolicy keys → OrbPolicy keys
        new_state = {}
        key_map = {
            "mlp_extractor.policy_net.0.weight": "shared.0.weight",
            "mlp_extractor.policy_net.0.bias":   "shared.0.bias",
            "mlp_extractor.policy_net.2.weight": "shared.2.weight",
            "mlp_extractor.policy_net.2.bias":   "shared.2.bias",
            "action_net.weight":                 "policy_head.weight",
            "action_net.bias":                   "policy_head.bias",
            "value_net.weight":                  "value_head.weight",
            "value_net.bias":                    "value_head.bias",
        }
        for sb3_key, our_key in key_map.items():
            if sb3_key in state:
                new_state[our_key] = state[sb3_key]
        model.load_state_dict(new_state, strict=False)
    else:
        model.load_state_dict(state)

    model.eval()
    return model


# ── Step 2: Export to ONNX ────────────────────────────────────────────────────

def export_onnx(model: OrbPolicy, onnx_path: str) -> None:
    dummy = torch.zeros(1, INPUT_SIZE)
    # Only export policy logits (not value head) to keep the graph simple
    class PolicyOnly(torch.nn.Module):
        def __init__(self, base):
            super().__init__()
            self.base = base
        def forward(self, x):
            return self.base.get_action_logits(x)

    wrapper = PolicyOnly(model)
    wrapper.eval()

    export_kwargs = dict(
        input_names  = ["input"],
        output_names = ["logits"],
        dynamic_axes = {"input": {0: "batch"}, "logits": {0: "batch"}},
        opset_version= 13,   # onnx-tf supports up to opset 13
    )
    with torch.no_grad():
        try:
            # PyTorch >= 2.3: pass dynamo=False to force the legacy exporter
            torch.onnx.export(wrapper, dummy, onnx_path, dynamo=False, **export_kwargs)
        except TypeError:
            # Older PyTorch: no dynamo parameter
            torch.onnx.export(wrapper, dummy, onnx_path, **export_kwargs)

    print(f"ONNX model saved to {onnx_path}")

    # Validate
    onnx_model = onnx.load(onnx_path)
    onnx.checker.check_model(onnx_model)
    print("ONNX model validated")


# ── Step 3: ONNX → TF SavedModel → TFLite ────────────────────────────────────

def export_tflite(model: OrbPolicy, tflite_path: str, quantize: bool) -> None:
    """
    Direct PyTorch → Keras → TFLite conversion.
    Copies weights without any ONNX intermediate step, so it works with any
    version of ONNX/TensorFlow and requires no extra packages.
    """
    import tensorflow as tf

    # Build equivalent Keras model (same architecture as OrbPolicy)
    inp = tf.keras.Input(shape=(INPUT_SIZE,), name="input")
    x   = tf.keras.layers.Dense(HIDDEN1, activation="relu")(inp)
    x   = tf.keras.layers.Dense(HIDDEN2, activation="relu")(x)
    out = tf.keras.layers.Dense(NUM_ACTIONS, name="logits")(x)
    keras_model = tf.keras.Model(inp, out)

    # Copy weights: PyTorch Linear weight is [out, in]; Keras Dense is [in, out]
    def copy_linear(pt_layer, keras_layer):
        w = pt_layer.weight.detach().numpy().T   # transpose to [in, out]
        b = pt_layer.bias.detach().numpy()
        keras_layer.set_weights([w, b])

    copy_linear(model.shared[0],   keras_model.layers[1])
    copy_linear(model.shared[2],   keras_model.layers[2])
    copy_linear(model.policy_head, keras_model.layers[3])

    print("Keras model built and weights copied from PyTorch checkpoint.")

    # Keras → TFLite
    print("Converting Keras -> TFLite ...")
    converter = tf.lite.TFLiteConverter.from_keras_model(keras_model)

    if quantize:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        def representative_dataset():
            for _ in range(200):
                yield [np.random.rand(1, INPUT_SIZE).astype(np.float32)]
        converter.representative_dataset = representative_dataset
        print("Applying INT8 post-training quantization ...")

    tflite_model = converter.convert()
    with open(tflite_path, "wb") as f:
        f.write(tflite_model)

    size_kb = len(tflite_model) / 1024
    print(f"TFLite model saved to {tflite_path}  ({size_kb:.1f} KB)")


# ── Quick sanity check ────────────────────────────────────────────────────────

ACTION_NAMES = ["UP", "DOWN", "LEFT", "RIGHT", "STOP"]


def sanity_check(tflite_path: str, pt_model: OrbPolicy | None = None) -> None:
    """
    Cross-validate TFLite output against PyTorch on 5 random inputs.
    Reports max absolute error; anything below 1e-4 is a clean pass.
    """
    try:
        import tensorflow as tf
        interpreter = tf.lite.Interpreter(model_path=tflite_path)
        interpreter.allocate_tensors()
        inp_detail = interpreter.get_input_details()[0]
        out_detail = interpreter.get_output_details()[0]

        rng = np.random.default_rng(0)
        max_err = 0.0
        print("\nSanity check — comparing PyTorch vs TFLite on 5 random inputs:")
        print(f"  {'Input#':<8} {'PT action':<12} {'TFL action':<12} {'Max |err|'}")
        print(f"  {'-'*6}  {'-'*10}  {'-'*10}  {'-'*10}")

        for i in range(5):
            x = rng.random((1, INPUT_SIZE), dtype=np.float32)

            # TFLite inference
            interpreter.set_tensor(inp_detail["index"], x)
            interpreter.invoke()
            tfl_logits = interpreter.get_tensor(out_detail["index"])[0]
            tfl_action = int(np.argmax(tfl_logits))

            # PyTorch inference (if model provided)
            if pt_model is not None:
                with torch.no_grad():
                    pt_logits = pt_model.get_action_logits(
                        torch.tensor(x)).numpy()[0]
                pt_action = int(np.argmax(pt_logits))
                err = float(np.max(np.abs(pt_logits - tfl_logits)))
                max_err = max(max_err, err)
                print(f"  #{i+1:<7} {ACTION_NAMES[pt_action]:<12} {ACTION_NAMES[tfl_action]:<12} {err:.2e}")
            else:
                print(f"  #{i+1:<7} {'N/A':<12} {ACTION_NAMES[tfl_action]:<12} {'N/A'}")

        if pt_model is not None:
            status = "PASS" if max_err < 1e-4 else "WARN (check weights)"
            print(f"\n  Max error across all inputs: {max_err:.2e}  [{status}]")
        else:
            print("\n  (Run with --checkpoint to enable cross-validation)")

    except Exception as e:
        print(f"Sanity check failed: {e}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint", type=str,   default="il_checkpoint.pt")
    parser.add_argument("--output",     type=str,   default="orb_policy.tflite")
    parser.add_argument("--onnx",       type=str,   default="orb_policy.onnx")
    parser.add_argument("--quantize",   action="store_true",
                        help="Apply INT8 post-training quantization (smaller, ~2× faster)")
    parser.add_argument("--rl",         action="store_true",
                        help="Checkpoint is an SB3 PPO policy (use after train_rl.py)")
    args = parser.parse_args()

    print(f"Loading checkpoint: {args.checkpoint}  (rl={args.rl})")
    model = load_model(args.checkpoint, args.rl)

    export_onnx(model, args.onnx)
    export_tflite(model, args.output, args.quantize)
    sanity_check(args.output, pt_model=model)

    print(f"\nDone!  Copy {args.output} to:")
    print("  app/src/main/assets/orb_policy.tflite")


if __name__ == "__main__":
    main()
