"""Evaluate a trained Custom Voice 1D CNN on its fixed held-out test set."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from voice_1dcnn_common import (
    BATCH_SIZE,
    CHECKPOINT_PATH,
    RESULT_DIR,
    VOICE_MANIFEST_PATH,
    WAVLM_SPLIT_PATH,
    WORKBOOK_PATH,
    evaluate_saved_checkpoint,
    write_evaluation_outputs,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, default=CHECKPOINT_PATH)
    parser.add_argument("--workbook", type=Path, default=WORKBOOK_PATH)
    parser.add_argument("--wavlm-split", type=Path, default=WAVLM_SPLIT_PATH)
    parser.add_argument("--audio-manifest", type=Path, default=VOICE_MANIFEST_PATH)
    parser.add_argument("--batch-size", type=int, default=BATCH_SIZE)
    parser.add_argument("--device", choices=("cpu", "cuda"), default=None)
    arguments = parser.parse_args()
    try:
        metrics = evaluate_saved_checkpoint(
            checkpoint_path=arguments.checkpoint,
            workbook_path=arguments.workbook,
            wavlm_split_path=arguments.wavlm_split,
            voice_manifest_path=arguments.audio_manifest,
            batch_size=arguments.batch_size,
            device_override=arguments.device,
        )
        write_evaluation_outputs(metrics, RESULT_DIR)
    except (FileNotFoundError, RuntimeError, ValueError, KeyError) as exc:
        print(f"Evaluation failed: {exc}", file=sys.stderr)
        return 1

    print("CUSTOM 1D CNN VOICE CLASSIFICATION RESULTS")
    print(f"Training Accuracy: {metrics['training_accuracy'] * 100:.2f}%")
    print(f"Validation Accuracy: {metrics['validation_accuracy'] * 100:.2f}%")
    print(f"Test Accuracy: {metrics['test_accuracy_percent']:.2f}%")
    print(f"Macro-F1: {metrics['test']['macro_f1']:.4f}")
    print(f"Balanced Accuracy: {metrics['test']['balanced_accuracy']:.4f}")
    print(f"Correct Predictions: {metrics['test']['correct_predictions']}")
    print(f"Incorrect Predictions: {metrics['test']['incorrect_predictions']}")
    print(f"Total Test Recordings: {metrics['test']['total_samples']}")
    print(
        "Majority-class baseline: "
        f"{metrics['majority_class_baseline']['accuracy_percent']:.2f}% "
        f"({metrics['majority_class_baseline']['class']})"
    )
    if metrics["test_predictions_only_one_category"]:
        print("WARNING: The CNN predicted only one category on the test partition.")
    comparison = metrics["comparison_to_wavlm"]
    if comparison["comparable"]:
        print(
            "WavLM comparison is directly aligned: "
            f"{comparison['wavlm_test_accuracy_percent']:.2f}% on the same "
            f"{comparison['test_recordings']} recordings and source classes."
        )
    else:
        print("WavLM comparison is not directly comparable.")
    print(f"Results directory: {RESULT_DIR}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
