"""Train the scratch-only Custom Voice 1D CNN classifier.

Run from ``ai-service``:
    python src/voice/train_voice_1dcnn_classifier.py
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
import torch
from sklearn.metrics import accuracy_score
from torch import nn

from voice_1dcnn_common import (
    BATCH_SIZE,
    CHECKPOINT_PATH,
    CLASS_NAMES,
    EARLY_STOPPING_PATIENCE,
    LEARNING_RATE,
    MAX_EPOCHS,
    MODEL_DIR,
    RESULT_DIR,
    SEED,
    WEIGHT_DECAY,
    WAVLM_SPLIT_PATH,
    WORKBOOK_PATH,
    VOICE_MANIFEST_PATH,
    CustomVoice1DCNN,
    MelConfig,
    VoiceClassificationDataset,
    checkpoint_config,
    collate_recordings,
    compute_training_normalization,
    count_parameters,
    evaluate_saved_checkpoint,
    load_classification_frame,
    make_loader,
    make_train_validation_ids,
    partition_summary,
    run_epoch,
    save_training_plots,
    seed_everything,
    write_evaluation_outputs,
)


def parser() -> argparse.ArgumentParser:
    command = argparse.ArgumentParser(description=__doc__)
    command.add_argument("--workbook", type=Path, default=WORKBOOK_PATH)
    command.add_argument("--wavlm-split", type=Path, default=WAVLM_SPLIT_PATH)
    command.add_argument("--audio-manifest", type=Path, default=VOICE_MANIFEST_PATH)
    command.add_argument("--epochs", type=int, default=MAX_EPOCHS)
    command.add_argument("--batch-size", type=int, default=BATCH_SIZE)
    command.add_argument("--learning-rate", type=float, default=LEARNING_RATE)
    command.add_argument("--weight-decay", type=float, default=WEIGHT_DECAY)
    command.add_argument(
        "--patience", type=int, default=EARLY_STOPPING_PATIENCE
    )
    command.add_argument("--seed", type=int, default=SEED)
    command.add_argument("--num-threads", type=int, default=2)
    command.add_argument(
        "--device",
        choices=("auto", "cpu", "cuda"),
        default="auto",
        help="Use CUDA only when available; default automatically selects a device.",
    )
    command.add_argument(
        "--overwrite",
        action="store_true",
        help="Replace only this classifier's named checkpoint/result artifacts.",
    )
    command.add_argument(
        "--check-dataset-only",
        action="store_true",
        help="Validate labels, audio paths, class rules, and leakage-safe split; do not train.",
    )
    return command


def _device(value: str) -> torch.device:
    if value == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but is not available in this PyTorch build.")
    return torch.device(
        "cuda" if value == "auto" and torch.cuda.is_available() else (
            "cpu" if value == "auto" else value
        )
    )


def _assert_outputs_available(overwrite: bool) -> None:
    intended = [
        CHECKPOINT_PATH,
        RESULT_DIR / "cnn_classification_metrics.json",
        RESULT_DIR / "cnn_classification_report.csv",
        RESULT_DIR / "cnn_confusion_matrix.png",
        RESULT_DIR / "cnn_training_history.csv",
        RESULT_DIR / "cnn_accuracy_plot.png",
        RESULT_DIR / "cnn_loss_plot.png",
        RESULT_DIR / "cnn_test_predictions.csv",
        RESULT_DIR / "cnn_model_config.json",
    ]
    existing = [path for path in intended if path.exists()]
    if existing and not overwrite:
        raise FileExistsError(
            "CNN output already exists; use --overwrite to replace only these "
            "1D CNN artifacts: " + ", ".join(str(path) for path in existing)
        )


def _accuracy(logits: torch.Tensor, labels: torch.Tensor) -> float:
    return float(
        accuracy_score(
            labels.detach().cpu().numpy(),
            logits.detach().argmax(dim=1).cpu().numpy(),
        )
    )


def train(arguments: argparse.Namespace) -> dict[str, Any]:
    if arguments.epochs < 1 or arguments.batch_size < 1 or arguments.patience < 1:
        raise ValueError("Epochs, batch size, and patience must all be positive.")
    if arguments.learning_rate <= 0 or arguments.weight_decay < 0:
        raise ValueError("Learning rate must be positive and weight decay nonnegative.")
    if arguments.num_threads < 1:
        raise ValueError("The number of CPU threads must be positive.")
    _assert_outputs_available(arguments.overwrite)
    seed_everything(arguments.seed)
    torch.set_num_threads(arguments.num_threads)
    device = _device(arguments.device)

    frame = load_classification_frame(
        arguments.workbook, arguments.wavlm_split, arguments.audio_manifest
    )
    training_ids, validation_ids = make_train_validation_ids(
        frame, seed=arguments.seed
    )
    test_ids = frame.loc[frame["split"].eq("test"), "voice_id"].astype(str).tolist()
    partition_ids = {
        "train": training_ids,
        "validation": validation_ids,
        "test": test_ids,
    }
    partitions = partition_summary(frame, partition_ids)
    training_frame = frame.loc[frame["voice_id"].isin(training_ids)].reset_index(
        drop=True
    )
    validation_frame = frame.loc[
        frame["voice_id"].isin(validation_ids)
    ].reset_index(drop=True)
    test_frame = frame.loc[frame["voice_id"].isin(test_ids)].reset_index(drop=True)
    if set(training_frame["student_id"]) & set(validation_frame["student_id"]):
        raise ValueError("Student overlap exists between optimization and validation.")
    if set(training_frame["student_id"]) & set(test_frame["student_id"]):
        raise ValueError("Student overlap exists between optimization and test.")
    if set(validation_frame["student_id"]) & set(test_frame["student_id"]):
        raise ValueError("Student overlap exists between validation and test.")

    mel_config = MelConfig(
        sample_rate=16_000,
        n_mels=64,
        n_fft=1024,
        hop_length=256,
        segment_seconds=10.0,
    )
    train_dataset = VoiceClassificationDataset(training_frame, mel_config)
    mean, std = compute_training_normalization(train_dataset)
    train_dataset.mean = mean
    train_dataset.std = std
    validation_dataset = VoiceClassificationDataset(
        validation_frame, mel_config, mean=mean, std=std
    )
    test_dataset = VoiceClassificationDataset(test_frame, mel_config, mean=mean, std=std)

    train_loader = make_loader(
        train_dataset,
        batch_size=arguments.batch_size,
        shuffle=True,
        seed=arguments.seed,
    )
    train_eval_loader = make_loader(
        train_dataset, batch_size=arguments.batch_size, shuffle=False
    )
    validation_loader = make_loader(
        validation_dataset, batch_size=arguments.batch_size, shuffle=False
    )
    class_counts = np.bincount(
        training_frame["label"].to_numpy(dtype=np.int64), minlength=len(CLASS_NAMES)
    )
    if np.any(class_counts == 0):
        raise ValueError(
            "Every class needs at least one optimization record for weighted "
            f"CrossEntropyLoss; observed counts: {class_counts.tolist()}."
        )
    class_weights = class_counts.sum() / (
        len(CLASS_NAMES) * class_counts.astype(np.float64)
    )
    class_weights_tensor = torch.tensor(
        class_weights, dtype=torch.float32, device=device
    )
    model = CustomVoice1DCNN(n_classes=3).to(device)
    parameter_info = count_parameters(model)
    print(model)
    print(f"Convolutional layers: {parameter_info['convolutional_layers']}")
    print(f"Trainable parameters: {parameter_info['trainable']:,}")
    print(f"Total parameters: {parameter_info['total']:,}")
    print("Output dimensions: 3 class logits")
    print(f"Device: {device}; pretrained weights: no")
    print("Partition recordings/students/class counts:")
    for name, details in partitions.items():
        print(
            f"  {name}: {details['recordings']} recordings, "
            f"{details['students']} students, {details['class_counts']}"
        )
    print(
        "Optimization training class weights: "
        + json.dumps(
            {
                name: float(weight)
                for name, weight in zip(CLASS_NAMES, class_weights)
            }
        )
    )
    sample = collate_recordings([train_dataset[0]])
    model.eval()
    with torch.inference_mode():
        sample_logits = model(sample["logmel"].to(device), sample["frame_lengths"].to(device))
    if tuple(sample_logits.shape) != (1, 3):
        raise RuntimeError(
            f"Unexpected model output shape {tuple(sample_logits.shape)}; expected (1, 3)."
        )
    print("Real-recording architecture check: PASS, output shape [1, 3]")

    criterion = nn.CrossEntropyLoss(weight=class_weights_tensor)
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=arguments.learning_rate,
        weight_decay=arguments.weight_decay,
    )
    hyperparameters = {
        "maximum_epochs": arguments.epochs,
        "batch_size": arguments.batch_size,
        "learning_rate": arguments.learning_rate,
        "optimizer": "AdamW",
        "weight_decay": arguments.weight_decay,
        "loss": "CrossEntropyLoss",
        "class_weights": {
            name: float(weight)
            for name, weight in zip(CLASS_NAMES, class_weights)
        },
        "early_stopping_patience": arguments.patience,
        "seed": arguments.seed,
        "validation_folds": 4,
        "validation_seed": arguments.seed,
        "validation_selection": "best fold with all classes in train and validation",
        "split": "existing WavLM train/test IDs; student-grouped validation from train",
        "device": str(device),
        "cpu_threads": arguments.num_threads,
    }
    model_configuration = checkpoint_config(
        hyperparameters, mel_config, parameter_info
    )

    history: list[dict[str, float | int]] = []
    best_state: dict[str, torch.Tensor] | None = None
    best_accuracy = -1.0
    best_validation_loss = float("inf")
    best_epoch = 0
    epochs_without_improvement = 0
    stopped_early = False
    for epoch in range(1, arguments.epochs + 1):
        train_output = run_epoch(
            model, train_loader, criterion, device, optimizer=optimizer
        )
        train_eval = run_epoch(model, train_eval_loader, criterion, device)
        validation_output = run_epoch(
            model, validation_loader, criterion, device
        )
        row = {
            "epoch": epoch,
            "train_loss": train_output["loss"],
            "train_accuracy": train_eval["accuracy"],
            "validation_loss": validation_output["loss"],
            "validation_accuracy": validation_output["accuracy"],
        }
        history.append(row)
        improved = validation_output["accuracy"] > best_accuracy or (
            validation_output["accuracy"] == best_accuracy
            and validation_output["loss"] < best_validation_loss - 1e-8
        )
        if improved:
            best_accuracy = validation_output["accuracy"]
            best_validation_loss = validation_output["loss"]
            best_epoch = epoch
            best_state = {
                key: value.detach().cpu().clone()
                for key, value in model.state_dict().items()
            }
            epochs_without_improvement = 0
        else:
            epochs_without_improvement += 1
        print(
            f"Epoch {epoch:02d}/{arguments.epochs}: "
            f"train_loss={row['train_loss']:.4f} "
            f"train_accuracy={row['train_accuracy'] * 100:.2f}% "
            f"val_loss={row['validation_loss']:.4f} "
            f"val_accuracy={row['validation_accuracy'] * 100:.2f}%"
        )
        if epochs_without_improvement >= arguments.patience:
            stopped_early = True
            print(f"Early stopping after epoch {epoch}; best epoch was {best_epoch}.")
            break

    if best_state is None:
        raise RuntimeError("Training completed without a validation checkpoint.")
    epochs_completed = len(history)
    split_ids = {
        "train": training_ids,
        "validation": validation_ids,
        "test": test_ids,
        "all": frame["voice_id"].astype(str).tolist(),
    }
    checkpoint = {
        "model_state_dict": best_state,
        "model_config": model_configuration,
        "normalization": {
            "mean": mean.tolist(),
            "std": std.tolist(),
        },
        "class_weights": class_weights.tolist(),
        "split_ids": split_ids,
        "partitions": partitions,
        "best_epoch": best_epoch,
        "best_validation_accuracy": best_accuracy,
        "best_validation_loss": best_validation_loss,
        "epochs_completed": epochs_completed,
        "early_stopping_occurred": stopped_early,
        "seed": arguments.seed,
    }
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    torch.save(checkpoint, CHECKPOINT_PATH)

    history_frame = pd.DataFrame(history)
    history_frame.to_csv(RESULT_DIR / "cnn_training_history.csv", index=False)
    save_training_plots(history_frame, RESULT_DIR)
    model_configuration["training_result"] = {
        "epochs_completed": epochs_completed,
        "best_epoch": best_epoch,
        "best_validation_accuracy": best_accuracy,
        "best_validation_loss": best_validation_loss,
        "early_stopping_occurred": stopped_early,
        "training_history": history,
        "partitions": partitions,
    }
    (RESULT_DIR / "cnn_model_config.json").write_text(
        json.dumps(model_configuration, indent=2, ensure_ascii=True) + "\n",
        encoding="utf-8",
    )
    metrics = evaluate_saved_checkpoint(
        checkpoint_path=CHECKPOINT_PATH,
        workbook_path=arguments.workbook,
        wavlm_split_path=arguments.wavlm_split,
        voice_manifest_path=arguments.audio_manifest,
        batch_size=arguments.batch_size,
        device_override=str(device),
    )
    write_evaluation_outputs(metrics, RESULT_DIR)
    print("\nCUSTOM 1D CNN VOICE CLASSIFICATION RESULTS")
    print(f"Training Accuracy: {metrics['training_accuracy'] * 100:.2f}%")
    print(f"Validation Accuracy: {metrics['validation_accuracy'] * 100:.2f}%")
    print(f"Test Accuracy: {metrics['test_accuracy_percent']:.2f}%")
    print(f"Macro-F1: {metrics['test']['macro_f1']:.4f}")
    print(f"Balanced Accuracy: {metrics['test']['balanced_accuracy']:.4f}")
    print(f"Correct Predictions: {metrics['test']['correct_predictions']}")
    print(f"Incorrect Predictions: {metrics['test']['incorrect_predictions']}")
    print(f"Total Test Recordings: {metrics['test']['total_samples']}")
    if metrics["test_predictions_only_one_category"]:
        print(
            "WARNING: The CNN predicted only one category on the test partition."
        )
    comparison = metrics["comparison_to_wavlm"]
    print(
        f"WavLM comparison: {comparison['wavlm_test_accuracy_percent']:.2f}% "
        f"on the same test recordings={comparison['same_test_recordings']} "
        f"(CNN test set={comparison['test_recordings']} recordings)."
    )
    print(f"Epochs completed: {epochs_completed}; best epoch: {best_epoch}")
    print(f"Early stopping occurred: {stopped_early}")
    print(f"Best model: {CHECKPOINT_PATH}")
    print(f"Metrics: {RESULT_DIR / 'cnn_classification_metrics.json'}")
    return metrics


def check_dataset(arguments: argparse.Namespace) -> None:
    frame = load_classification_frame(
        arguments.workbook, arguments.wavlm_split, arguments.audio_manifest
    )
    training_ids, validation_ids = make_train_validation_ids(
        frame, seed=arguments.seed
    )
    test_ids = frame.loc[frame["split"].eq("test"), "voice_id"].astype(str).tolist()
    partitions = partition_summary(
        frame,
        {"train": training_ids, "validation": validation_ids, "test": test_ids},
    )
    print("Voice classification dataset checks: PASS")
    print(f"Workbook: {arguments.workbook}")
    print("Approved source mapping: Weak -> Developing, Moderate, High")
    print(
        "Validated recordings retained for the WavLM-aligned experiment: "
        f"{len(frame)}"
    )
    print("All retained labels match the documented VC01 thresholds.")
    print("No student or duplicate-audio overlap across the fixed split.")
    for name, details in partitions.items():
        print(
            f"{name}: {details['recordings']} recordings / "
            f"{details['students']} students; classes={details['class_counts']}"
        )
    print("No model training or result files were created.")


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.check_dataset_only:
            check_dataset(arguments)
        else:
            train(arguments)
    except (FileNotFoundError, FileExistsError, RuntimeError, ValueError) as exc:
        print(f"Training failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
