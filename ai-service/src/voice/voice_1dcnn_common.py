"""Shared data, model, and evaluation utilities for the scratch voice CNN."""

from __future__ import annotations

import json
import random
import warnings
from dataclasses import asdict
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import torch
from sklearn.metrics import (
    accuracy_score,
    balanced_accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
    precision_recall_fscore_support,
)
from sklearn.model_selection import StratifiedGroupKFold
from torch import Tensor, nn
from torch.utils.data import DataLoader, Dataset

if __package__:
    from .train_voice_custom_cnn import MelConfig, audio_to_logmel_segments
else:
    from train_voice_custom_cnn import MelConfig, audio_to_logmel_segments


AI_SERVICE_ROOT = Path(__file__).resolve().parents[2]
VOICE_ROOT = AI_SERVICE_ROOT / "data" / "voice"
WORKBOOK_PATH = AI_SERVICE_ROOT / "data" / "labels" / "VC01.xlsx"
VOICE_MANIFEST_PATH = VOICE_ROOT / "splits" / "voice_split.csv"
WAVLM_SPLIT_PATH = AI_SERVICE_ROOT / "models" / "voice_wavlm_split.csv"
WAVLM_CHECKPOINT_PATH = AI_SERVICE_ROOT / "models" / "voice_wavlm_classifier.pth"
MODEL_DIR = AI_SERVICE_ROOT / "models" / "voice_1dcnn"
RESULT_DIR = AI_SERVICE_ROOT / "results" / "voice_1dcnn"
CHECKPOINT_PATH = MODEL_DIR / "best_voice_1dcnn.pth"

SOURCE_CLASS_TO_INDEX = {"Weak": 0, "Moderate": 1, "High": 2}
CLASS_NAMES = ("Developing", "Moderate", "High")
SOURCE_CLASS_NAMES = ("Weak", "Moderate", "High")
SEED = 42
MAX_EPOCHS = 50
BATCH_SIZE = 8
LEARNING_RATE = 0.001
WEIGHT_DECAY = 0.0001
EARLY_STOPPING_PATIENCE = 8
VALIDATION_FOLDS = 4
VALIDATION_SEED = 42
WAVLM_TEST_ACCURACY = 0.625


def seed_everything(seed: int = SEED) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def load_classification_frame(
    workbook_path: Path = WORKBOOK_PATH,
    wavlm_split_path: Path = WAVLM_SPLIT_PATH,
    voice_manifest_path: Path = VOICE_MANIFEST_PATH,
) -> pd.DataFrame:
    """Join verified labels, the prior WavLM split, and processed audio paths."""
    for path in (workbook_path, wavlm_split_path, voice_manifest_path):
        if not path.is_file():
            raise FileNotFoundError(f"Required voice dataset file is missing: {path}")

    labels = pd.read_excel(workbook_path, sheet_name="VC01_Labels")
    labels.columns = [str(column).strip() for column in labels.columns]
    required_labels = {
        "student id",
        "voice_id",
        "activity_id",
        "story_creativity_mean",
        "teacher_verification",
        "label_status",
        "participant_identity_verified",
        "creativity class",
    }
    missing = sorted(required_labels - set(labels.columns))
    if missing:
        raise ValueError(f"VC01_Labels is missing required columns: {missing}")

    verified = (
        labels["teacher_verification"]
        .fillna("")
        .astype(str)
        .str.strip()
        .str.casefold()
        .eq("teacher verified")
        & labels["label_status"]
        .fillna("")
        .astype(str)
        .str.strip()
        .str.casefold()
        .eq("validated")
        & labels["participant_identity_verified"]
        .fillna("")
        .astype(str)
        .str.strip()
        .str.casefold()
        .isin({"yes", "true", "verified"})
    )
    labels = labels.loc[verified].copy()
    if labels.empty:
        raise ValueError("No teacher-validated voice classification labels were found.")

    labels["voice_id"] = labels["voice_id"].fillna("").astype(str).str.strip()
    labels["student_id"] = labels["student id"].fillna("").astype(str).str.strip()
    labels["activity_id"] = labels["activity_id"].fillna("").astype(str).str.strip()
    labels["source_class"] = (
        labels["creativity class"].fillna("").astype(str).str.strip()
    )
    labels["story_creativity_mean"] = pd.to_numeric(
        labels["story_creativity_mean"], errors="coerce"
    )
    if labels["voice_id"].eq("").any() or labels["student_id"].eq("").any():
        raise ValueError("Validated labels contain a missing voice or student ID.")
    if labels["voice_id"].duplicated().any():
        repeated = labels.loc[labels["voice_id"].duplicated(False), "voice_id"].tolist()
        raise ValueError(f"Validated label rows have duplicate voice IDs: {repeated}")
    unknown = sorted(set(labels["source_class"]) - set(SOURCE_CLASS_TO_INDEX))
    if unknown:
        raise ValueError(f"Unexpected validated voice classes: {unknown}")
    if labels["story_creativity_mean"].isna().any() or not labels[
        "story_creativity_mean"
    ].between(1.0, 5.0).all():
        raise ValueError("Validated story_creativity_mean values must be in the 1-5 range.")

    story_mean = labels["story_creativity_mean"]
    rule_class = np.select(
        [story_mean <= 3.4, story_mean <= 4.0],
        ["Weak", "Moderate"],
        default="High",
    )
    mismatch = labels.loc[
        labels["source_class"].to_numpy() != rule_class,
        ["voice_id", "story_creativity_mean", "source_class"],
    ]
    if not mismatch.empty:
        raise ValueError(
            "Existing class labels disagree with the approved VC01 voice rules: "
            + mismatch.to_dict(orient="records").__str__()
        )
    labels["label"] = labels["source_class"].map(SOURCE_CLASS_TO_INDEX).astype(int)

    wavlm_split = pd.read_csv(wavlm_split_path)
    required_split = {"voice_id", "student_id", "wav_filename", "split"}
    missing = sorted(required_split - set(wavlm_split.columns))
    if missing:
        raise ValueError(f"WavLM split is missing columns: {missing}")
    wavlm_split["voice_id"] = wavlm_split["voice_id"].fillna("").astype(str).str.strip()
    wavlm_split["student_id"] = (
        wavlm_split["student_id"].fillna("").astype(str).str.strip()
    )
    if wavlm_split["voice_id"].duplicated().any():
        raise ValueError("WavLM split contains duplicate voice IDs.")
    if not wavlm_split["split"].isin({"train", "test"}).all():
        raise ValueError("WavLM split must contain only train and test partitions.")

    audio_manifest = pd.read_csv(voice_manifest_path)
    required_audio = {"voice_id", "processed_path", "audio_sha256"}
    missing = sorted(required_audio - set(audio_manifest.columns))
    if missing:
        raise ValueError(f"Processed voice manifest is missing columns: {missing}")
    audio_manifest = audio_manifest[
        ["voice_id", "processed_path", "audio_sha256"]
    ].copy()
    audio_manifest["voice_id"] = (
        audio_manifest["voice_id"].fillna("").astype(str).str.strip()
    )
    if audio_manifest["voice_id"].duplicated().any():
        raise ValueError("Processed voice manifest contains duplicate voice IDs.")

    frame = wavlm_split.merge(
        labels[
            [
                "voice_id",
                "student_id",
                "activity_id",
                "source_class",
                "label",
                "story_creativity_mean",
            ]
        ],
        on="voice_id",
        how="left",
        suffixes=("_split", "_label"),
        validate="one_to_one",
        indicator="_label_match",
    )
    unmatched = frame.loc[frame["_label_match"].ne("both"), "voice_id"].tolist()
    if unmatched:
        raise ValueError(
            "WavLM split includes recordings without validated labels: "
            + ", ".join(unmatched)
        )
    if not frame["student_id_split"].eq(frame["student_id_label"]).all():
        raise ValueError("Student IDs in WavLM split do not match the label workbook.")
    frame = frame.rename(columns={"student_id_label": "student_id"})
    frame = frame.drop(columns=["student_id_split", "_label_match"])

    frame = frame.merge(
        audio_manifest,
        on="voice_id",
        how="left",
        validate="one_to_one",
        indicator="_audio_match",
    )
    missing_audio = frame.loc[frame["_audio_match"].ne("both"), "voice_id"].tolist()
    if missing_audio:
        raise FileNotFoundError(
            "Processed audio is missing for WavLM split recordings: "
            + ", ".join(missing_audio)
        )
    frame = frame.drop(columns="_audio_match")
    frame["processed_path"] = frame["processed_path"].map(
        lambda value: Path(str(value)).resolve()
    )
    missing_paths = frame.loc[
        ~frame["processed_path"].map(Path.is_file), "processed_path"
    ].astype(str).tolist()
    if missing_paths:
        raise FileNotFoundError(
            "Processed audio files are missing: " + ", ".join(missing_paths[:5])
        )

    split_groups = {
        name: set(frame.loc[frame["split"].eq(name), "student_id"])
        for name in ("train", "test")
    }
    overlap = split_groups["train"] & split_groups["test"]
    if overlap:
        raise ValueError(f"Student leakage in WavLM split: {sorted(overlap)}")
    duplicate_hashes = frame.loc[
        frame["audio_sha256"].duplicated(keep=False), "audio_sha256"
    ].unique()
    if len(duplicate_hashes):
        raise ValueError(
            "The WavLM split contains duplicate audio content; refusing split reuse."
        )
    if set(frame["source_class"]) != set(SOURCE_CLASS_TO_INDEX):
        raise ValueError(
            "All three validated classes must be represented in the WavLM split."
        )
    if frame.groupby("split")["voice_id"].nunique().to_dict() != {
        "test": 8,
        "train": 16,
    }:
        raise ValueError(
            "The expected WavLM 16-train/8-test split has changed; "
            "inspect and approve the new split before training."
        )
    return frame.reset_index(drop=True)


def make_train_validation_ids(
    frame: pd.DataFrame, seed: int = VALIDATION_SEED
) -> tuple[list[str], list[str]]:
    """Select a stratified, student-grouped validation fold from WavLM train."""
    train_frame = frame.loc[frame["split"].eq("train")].reset_index(drop=True)
    labels = train_frame["label"].to_numpy(dtype=np.int64)
    groups = train_frame["student_id"].to_numpy(dtype=str)
    if train_frame["student_id"].nunique() < VALIDATION_FOLDS:
        raise ValueError("Too few student groups for a four-fold validation split.")

    splitter = StratifiedGroupKFold(
        n_splits=VALIDATION_FOLDS, shuffle=True, random_state=seed
    )
    candidates: list[tuple[tuple[float, ...], np.ndarray, np.ndarray]] = []
    all_classes = set(range(len(CLASS_NAMES)))
    with warnings.catch_warnings():
        warnings.filterwarnings(
            "ignore",
            message="The least populated class in y has only .*",
            category=UserWarning,
        )
        folds = list(splitter.split(train_frame, labels, groups))

    expected_validation_size = max(1, round(len(train_frame) / VALIDATION_FOLDS))
    global_counts = np.bincount(labels, minlength=len(CLASS_NAMES)).astype(float)
    global_distribution = global_counts / global_counts.sum()
    for train_indices, validation_indices in folds:
        train_classes = set(labels[train_indices])
        validation_classes = set(labels[validation_indices])
        validation_distribution = (
            np.bincount(labels[validation_indices], minlength=len(CLASS_NAMES))
            / len(validation_indices)
        )
        distribution_error = float(
            np.abs(validation_distribution - global_distribution).sum()
        )
        score = (
            float(validation_classes == all_classes),
            float(train_classes == all_classes),
            -float(abs(len(validation_indices) - expected_validation_size)),
            -distribution_error,
        )
        candidates.append((score, train_indices, validation_indices))

    _, training_indices, validation_indices = max(
        candidates, key=lambda candidate: candidate[0]
    )
    training_ids = train_frame.iloc[training_indices]["voice_id"].astype(str).tolist()
    validation_ids = (
        train_frame.iloc[validation_indices]["voice_id"].astype(str).tolist()
    )
    training_students = set(
        train_frame.iloc[training_indices]["student_id"].astype(str)
    )
    validation_students = set(
        train_frame.iloc[validation_indices]["student_id"].astype(str)
    )
    if training_students & validation_students:
        raise ValueError("Student leakage in inner train/validation split.")
    if set(labels[training_indices]) != all_classes:
        raise ValueError("The inner training partition does not contain all classes.")
    return training_ids, validation_ids


class VoiceClassificationDataset(Dataset[dict[str, Any]]):
    """Recording-level data; every segment remains attached to its recording."""

    def __init__(
        self,
        frame: pd.DataFrame,
        config: MelConfig,
        mean: np.ndarray | None = None,
        std: np.ndarray | None = None,
    ) -> None:
        self.frame = frame.reset_index(drop=True)
        self.config = config
        self.mean = mean
        self.std = std
        if (mean is None) != (std is None):
            raise ValueError("Training normalization requires both mean and std.")
        self._cache: dict[int, tuple[list[np.ndarray], list[int], float]] = {}

    def __len__(self) -> int:
        return len(self.frame)

    def _features(self, index: int) -> tuple[list[np.ndarray], list[int], float]:
        if index not in self._cache:
            row = self.frame.iloc[index]
            try:
                self._cache[index] = audio_to_logmel_segments(
                    Path(row["processed_path"]), self.config
                )
            except (OSError, RuntimeError, ValueError) as exc:
                raise ValueError(
                    f"Could not process recording {row['voice_id']} "
                    f"at {row['processed_path']}: {exc}"
                ) from exc
            segments, lengths, duration = self._cache[index]
            if duration <= 0 or not segments:
                raise ValueError(f"Recording {row['voice_id']} is empty.")
            if any(length < 8 for length in lengths):
                raise ValueError(
                    f"Recording {row['voice_id']} has a segment too short "
                    "for three temporal pooling layers."
                )
        return self._cache[index]

    def __getitem__(self, index: int) -> dict[str, Any]:
        segments, lengths, duration = self._features(index)
        values = np.stack(segments).astype(np.float32, copy=True)
        if self.mean is not None and self.std is not None:
            for segment_index, valid_frames in enumerate(lengths):
                values[segment_index, :, :valid_frames] = (
                    values[segment_index, :, :valid_frames] - self.mean[:, None]
                ) / self.std[:, None]
        row = self.frame.iloc[index]
        return {
            "logmel": torch.from_numpy(values),
            "frame_lengths": torch.tensor(lengths, dtype=torch.long),
            "label": int(row["label"]),
            "voice_id": str(row["voice_id"]),
            "student_id": str(row["student_id"]),
            "source_class": str(row["source_class"]),
            "duration_seconds": float(duration),
            "processed_path": str(row["processed_path"]),
        }


def compute_training_normalization(
    dataset: VoiceClassificationDataset,
) -> tuple[np.ndarray, np.ndarray]:
    if dataset.frame.empty:
        raise ValueError("Cannot compute normalization without training recordings.")
    if not dataset.frame["split"].eq("train").all():
        raise ValueError("Feature statistics may only use the training partition.")
    total = np.zeros(64, dtype=np.float64)
    total_squared = np.zeros(64, dtype=np.float64)
    frame_count = 0
    for index in range(len(dataset)):
        segments, lengths, _ = dataset._features(index)
        for segment, valid_frames in zip(segments, lengths):
            valid = segment[:, :valid_frames].astype(np.float64, copy=False)
            total += valid.sum(axis=1)
            total_squared += np.square(valid).sum(axis=1)
            frame_count += valid.shape[1]
    if frame_count == 0:
        raise ValueError("Training recordings contain no valid log-mel frames.")
    mean = total / frame_count
    variance = np.maximum(total_squared / frame_count - mean**2, 1e-8)
    return mean.astype(np.float32), np.sqrt(variance).astype(np.float32)


def collate_recordings(records: list[dict[str, Any]]) -> dict[str, Any]:
    if not records:
        raise ValueError("Cannot collate an empty audio batch.")
    max_segments = max(record["logmel"].shape[0] for record in records)
    n_mels = records[0]["logmel"].shape[1]
    time_steps = max(record["logmel"].shape[2] for record in records)
    values = torch.zeros(
        (len(records), max_segments, n_mels, time_steps), dtype=torch.float32
    )
    lengths = torch.zeros((len(records), max_segments), dtype=torch.long)
    for index, record in enumerate(records):
        segment_count, _, frame_count = record["logmel"].shape
        values[index, :segment_count, :, :frame_count] = record["logmel"]
        lengths[index, :segment_count] = record["frame_lengths"]
    return {
        "logmel": values,
        "frame_lengths": lengths,
        "labels": torch.tensor([record["label"] for record in records]),
        "voice_id": [record["voice_id"] for record in records],
        "student_id": [record["student_id"] for record in records],
        "source_class": [record["source_class"] for record in records],
        "duration_seconds": [record["duration_seconds"] for record in records],
        "processed_path": [record["processed_path"] for record in records],
    }


class CustomVoice1DCNN(nn.Module):
    """Scratch-initialized, length-aware 1D CNN returning three class logits."""

    def __init__(self, n_classes: int = 3) -> None:
        super().__init__()
        if n_classes != 3:
            raise ValueError("Custom Voice 1D CNN requires exactly three classes.")
        self.block1 = nn.Sequential(
            nn.Conv1d(64, 64, kernel_size=5, padding=2),
            nn.BatchNorm1d(64),
            nn.ReLU(),
            nn.MaxPool1d(kernel_size=2),
        )
        self.block2 = nn.Sequential(
            nn.Conv1d(64, 128, kernel_size=3, padding=1),
            nn.BatchNorm1d(128),
            nn.ReLU(),
            nn.MaxPool1d(kernel_size=2),
        )
        self.block3 = nn.Sequential(
            nn.Conv1d(128, 256, kernel_size=3, padding=1),
            nn.BatchNorm1d(256),
            nn.ReLU(),
            nn.MaxPool1d(kernel_size=2),
        )
        self.head = nn.Sequential(
            nn.Linear(256, 128),
            nn.ReLU(),
            nn.Dropout(0.3),
            nn.Linear(128, n_classes),
        )
        self.reset_parameters()

    def reset_parameters(self) -> None:
        for module in self.modules():
            if isinstance(module, nn.Conv1d):
                nn.init.kaiming_normal_(module.weight, nonlinearity="relu")
                if module.bias is not None:
                    nn.init.zeros_(module.bias)
            elif isinstance(module, nn.Linear):
                nn.init.kaiming_uniform_(module.weight, nonlinearity="relu")
                nn.init.zeros_(module.bias)
            elif isinstance(module, nn.BatchNorm1d):
                nn.init.ones_(module.weight)
                nn.init.zeros_(module.bias)

    def _classify_segment(self, segment: Tensor) -> Tensor:
        values = self.block1(segment)
        values = self.block2(values)
        values = self.block3(values)
        pooled = values.mean(dim=-1)
        return self.head(pooled)

    def forward(self, logmel: Tensor, frame_lengths: Tensor) -> Tensor:
        """Crop each segment to valid frames before convolution or pooling."""
        if logmel.ndim != 4 or logmel.shape[2] != 64:
            raise ValueError("Input must have shape [batch, segments, 64, time].")
        if frame_lengths.shape != logmel.shape[:2]:
            raise ValueError("Frame lengths must have shape [batch, segments].")
        recording_logits: list[Tensor] = []
        for batch_index in range(logmel.shape[0]):
            segment_logits: list[Tensor] = []
            for segment_index in range(logmel.shape[1]):
                valid_frames = int(frame_lengths[batch_index, segment_index].item())
                if valid_frames <= 0:
                    continue
                if valid_frames > logmel.shape[-1]:
                    raise ValueError("A segment length exceeds the padded input size.")
                if valid_frames < 8:
                    raise ValueError("A valid audio segment must contain >=8 frames.")
                segment = logmel[
                    batch_index, segment_index, :, :valid_frames
                ].unsqueeze(0)
                segment_logits.append(self._classify_segment(segment))
            if not segment_logits:
                raise ValueError("A recording contains no valid segments.")
            recording_logits.append(torch.stack(segment_logits).mean(dim=0).squeeze(0))
        return torch.stack(recording_logits)


def count_parameters(model: nn.Module) -> dict[str, int]:
    return {
        "trainable": sum(
            parameter.numel()
            for parameter in model.parameters()
            if parameter.requires_grad
        ),
        "total": sum(parameter.numel() for parameter in model.parameters()),
        "convolutional_layers": sum(
            isinstance(module, nn.Conv1d) for module in model.modules()
        ),
    }


def make_loader(
    dataset: VoiceClassificationDataset,
    batch_size: int = BATCH_SIZE,
    shuffle: bool = False,
    seed: int = SEED,
) -> DataLoader[dict[str, Any]]:
    generator = torch.Generator().manual_seed(seed)
    return DataLoader(
        dataset,
        batch_size=batch_size,
        shuffle=shuffle,
        num_workers=0,
        collate_fn=collate_recordings,
        generator=generator if shuffle else None,
    )


def run_epoch(
    model: CustomVoice1DCNN,
    loader: DataLoader[dict[str, Any]],
    criterion: nn.Module,
    device: torch.device,
    optimizer: torch.optim.Optimizer | None = None,
) -> dict[str, Any]:
    training = optimizer is not None
    model.train(training)
    losses: list[float] = []
    all_labels: list[int] = []
    all_predictions: list[int] = []
    all_probabilities: list[list[float]] = []
    all_voice_ids: list[str] = []
    with torch.set_grad_enabled(training):
        for batch in loader:
            logmel = batch["logmel"].to(device)
            lengths = batch["frame_lengths"].to(device)
            labels = batch["labels"].to(device)
            if training:
                optimizer.zero_grad(set_to_none=True)
            logits = model(logmel, lengths)
            loss = criterion(logits, labels)
            if training:
                loss.backward()
                optimizer.step()
            probabilities = torch.softmax(logits.detach(), dim=1)
            predictions = probabilities.argmax(dim=1)
            losses.append(float(loss.detach().cpu()) * len(labels))
            all_labels.extend(labels.detach().cpu().tolist())
            all_predictions.extend(predictions.cpu().tolist())
            all_probabilities.extend(probabilities.cpu().tolist())
            all_voice_ids.extend(batch["voice_id"])

    if not all_labels:
        raise ValueError("Cannot evaluate an empty dataset partition.")
    return {
        "loss": float(sum(losses) / len(all_labels)),
        "labels": all_labels,
        "predictions": all_predictions,
        "probabilities": all_probabilities,
        "voice_ids": all_voice_ids,
        "accuracy": float(accuracy_score(all_labels, all_predictions)),
    }


def classification_metrics(
    labels: list[int] | np.ndarray,
    predictions: list[int] | np.ndarray,
    majority_class_index: int | None = None,
) -> dict[str, Any]:
    labels_array = np.asarray(labels, dtype=np.int64)
    predictions_array = np.asarray(predictions, dtype=np.int64)
    label_order = list(range(len(CLASS_NAMES)))
    precision, recall, f1, support = precision_recall_fscore_support(
        labels_array,
        predictions_array,
        labels=label_order,
        zero_division=0,
    )
    report = classification_report(
        labels_array,
        predictions_array,
        labels=label_order,
        target_names=list(CLASS_NAMES),
        output_dict=True,
        zero_division=0,
    )
    counts = np.bincount(labels_array, minlength=len(CLASS_NAMES))
    if majority_class_index is None:
        majority_class_index = int(counts.argmax())
    majority_accuracy = float(np.mean(labels_array == majority_class_index))
    return {
        "accuracy": float(accuracy_score(labels_array, predictions_array)),
        "macro_f1": float(
            f1_score(
                labels_array,
                predictions_array,
                labels=label_order,
                average="macro",
                zero_division=0,
            )
        ),
        "balanced_accuracy": float(
            balanced_accuracy_score(labels_array, predictions_array)
        ),
        "precision_by_class": {
            name: float(value) for name, value in zip(CLASS_NAMES, precision)
        },
        "recall_by_class": {
            name: float(value) for name, value in zip(CLASS_NAMES, recall)
        },
        "f1_by_class": {name: float(value) for name, value in zip(CLASS_NAMES, f1)},
        "support_by_class": {
            name: int(value) for name, value in zip(CLASS_NAMES, support)
        },
        "confusion_matrix": confusion_matrix(
            labels_array, predictions_array, labels=label_order
        ).tolist(),
        "classification_report": report,
        "total_samples": int(len(labels_array)),
        "correct_predictions": int(np.sum(labels_array == predictions_array)),
        "incorrect_predictions": int(np.sum(labels_array != predictions_array)),
        "majority_class_baseline_accuracy": majority_accuracy,
        "predicted_classes": sorted(set(predictions_array.tolist())),
        "predicts_only_one_class": len(set(predictions_array.tolist())) == 1,
    }


def partition_summary(
    frame: pd.DataFrame, partition_ids: dict[str, list[str]]
) -> dict[str, Any]:
    summary: dict[str, Any] = {}
    for partition, voice_ids in partition_ids.items():
        rows = frame.loc[frame["voice_id"].isin(voice_ids)]
        class_counts = (
            rows["source_class"].value_counts().reindex(SOURCE_CLASS_NAMES, fill_value=0)
        )
        summary[partition] = {
            "recordings": int(len(rows)),
            "students": int(rows["student_id"].nunique()),
            "class_counts": {
                display_name: int(class_counts[source_name])
                for source_name, display_name in zip(SOURCE_CLASS_NAMES, CLASS_NAMES)
            },
            "voice_ids": rows["voice_id"].astype(str).tolist(),
        }
    return summary


def checkpoint_config(
    hyperparameters: dict[str, Any],
    mel_config: MelConfig,
    parameters: dict[str, int],
) -> dict[str, Any]:
    return {
        "model_name": "Custom Voice 1D CNN",
        "model_type": "three-class classification",
        "pretrained": False,
        "framework": f"PyTorch {torch.__version__}",
        "architecture": {
            "blocks": [
                {
                    "conv1d": [64, 64],
                    "kernel_size": 5,
                    "padding": 2,
                    "batch_norm": True,
                    "activation": "ReLU",
                    "max_pool": 2,
                },
                {
                    "conv1d": [64, 128],
                    "kernel_size": 3,
                    "padding": 1,
                    "batch_norm": True,
                    "activation": "ReLU",
                    "max_pool": 2,
                },
                {
                    "conv1d": [128, 256],
                    "kernel_size": 3,
                    "padding": 1,
                    "batch_norm": True,
                    "activation": "ReLU",
                    "max_pool": 2,
                },
            ],
            "pooling": "valid-frame cropping then temporal global average pooling",
            "head": ["Linear(256,128)", "ReLU", "Dropout(0.3)", "Linear(128,3)"],
            "parameters": parameters,
            "output_dimensions": 3,
        },
        "classes": [
            {"index": index, "name": name, "source_label": source}
            for index, (name, source) in enumerate(
                zip(CLASS_NAMES, SOURCE_CLASS_NAMES)
            )
        ],
        "label_rule": {
            "source_sheet": "VC01_Labels",
            "source_column": "creativity class",
            "threshold_column": "story_creativity_mean",
            "weak_to_developing_approved_by_user": True,
            "rules": {
                "Developing": "source class Weak; score <= 3.40",
                "Moderate": "source class Moderate; 3.40 < score <= 4.00",
                "High": "source class High; score > 4.00 and <= 5.00",
            },
        },
        "audio": asdict(mel_config),
        "audio_preprocessing": {
            "source": "existing processed WAV recordings; originals are read-only",
            "decoder": "librosa",
            "channels": "mono",
            "sample_rate_hz": mel_config.sample_rate,
            "representation": "log-mel power spectrogram in dB",
            "peak_normalization": False,
            "mel_statistics": "per-band mean/std fitted only on optimization training recordings",
        },
        "normalization": "per-mel mean/std fit only on optimization training recordings",
        "segmentation": {
            "seconds_per_segment": mel_config.segment_seconds,
            "recording_aggregation": "mean of segment logits",
            "padding": "padded segments are excluded; valid frames are cropped before CNN",
        },
        "hyperparameters": hyperparameters,
    }


def save_training_plots(history: pd.DataFrame, result_dir: Path) -> None:
    result_dir.mkdir(parents=True, exist_ok=True)
    epochs = history["epoch"]
    plt.figure(figsize=(8, 5))
    plt.plot(epochs, history["train_accuracy"] * 100, label="Train")
    plt.plot(epochs, history["validation_accuracy"] * 100, label="Validation")
    plt.xlabel("Epoch")
    plt.ylabel("Accuracy (%)")
    plt.title("Custom Voice 1D CNN Accuracy")
    plt.legend()
    plt.tight_layout()
    plt.savefig(result_dir / "cnn_accuracy_plot.png", dpi=160)
    plt.close()

    plt.figure(figsize=(8, 5))
    plt.plot(epochs, history["train_loss"], label="Train")
    plt.plot(epochs, history["validation_loss"], label="Validation")
    plt.xlabel("Epoch")
    plt.ylabel("Weighted cross-entropy loss")
    plt.title("Custom Voice 1D CNN Loss")
    plt.legend()
    plt.tight_layout()
    plt.savefig(result_dir / "cnn_loss_plot.png", dpi=160)
    plt.close()


def _loader_for_ids(
    frame: pd.DataFrame,
    voice_ids: list[str],
    config: MelConfig,
    mean: np.ndarray,
    std: np.ndarray,
    batch_size: int,
) -> DataLoader[dict[str, Any]]:
    indexed = frame.set_index("voice_id", drop=False)
    missing = [voice_id for voice_id in voice_ids if voice_id not in indexed.index]
    if missing:
        raise ValueError(f"Checkpoint recordings no longer exist: {missing}")
    subset = indexed.loc[voice_ids].reset_index(drop=True)
    dataset = VoiceClassificationDataset(subset, config, mean=mean, std=std)
    return make_loader(dataset, batch_size=batch_size, shuffle=False)


def evaluate_saved_checkpoint(
    checkpoint_path: Path = CHECKPOINT_PATH,
    workbook_path: Path = WORKBOOK_PATH,
    wavlm_split_path: Path = WAVLM_SPLIT_PATH,
    voice_manifest_path: Path = VOICE_MANIFEST_PATH,
    batch_size: int = BATCH_SIZE,
    device_override: str | None = None,
) -> dict[str, Any]:
    if not checkpoint_path.is_file():
        raise FileNotFoundError(f"Trained CNN checkpoint not found: {checkpoint_path}")
    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=True)
    config = checkpoint["model_config"]
    frame = load_classification_frame(
        workbook_path, wavlm_split_path, voice_manifest_path
    )
    split_ids = checkpoint["split_ids"]
    if set(frame["voice_id"].astype(str)) != set(split_ids["all"]):
        raise ValueError(
            "Current data IDs differ from the training checkpoint; refusing evaluation."
        )
    for partition in ("train", "validation", "test"):
        expected = set(split_ids[partition])
        actual = set(frame.loc[
            frame["voice_id"].isin(split_ids[partition])
            if partition == "train"
            else frame["split"].eq("test") if partition == "test"
            else frame["voice_id"].isin(split_ids[partition]),
            "voice_id",
        ].astype(str))
        if actual != expected:
            raise ValueError(f"Saved {partition} IDs no longer match the data.")

    audio_config = MelConfig(**config["audio"])
    mean = np.asarray(checkpoint["normalization"]["mean"], dtype=np.float32)
    std = np.asarray(checkpoint["normalization"]["std"], dtype=np.float32)
    device = torch.device(
        device_override or ("cuda" if torch.cuda.is_available() else "cpu")
    )
    model = CustomVoice1DCNN(n_classes=3).to(device)
    model.load_state_dict(checkpoint["model_state_dict"])
    class_weights = torch.tensor(
        checkpoint["class_weights"], dtype=torch.float32, device=device
    )
    criterion = nn.CrossEntropyLoss(weight=class_weights)

    loaders = {
        partition: _loader_for_ids(
            frame, split_ids[partition], audio_config, mean, std, batch_size
        )
        for partition in ("train", "validation", "test")
    }
    outputs = {
        partition: run_epoch(model, loader, criterion, device)
        for partition, loader in loaders.items()
    }
    training_counts = np.bincount(
        outputs["train"]["labels"], minlength=len(CLASS_NAMES)
    )
    training_majority_index = int(training_counts.argmax())
    test_metrics = classification_metrics(
        outputs["test"]["labels"],
        outputs["test"]["predictions"],
        majority_class_index=training_majority_index,
    )
    actual_test_ids = set(outputs["test"]["voice_ids"])
    wavlm_test_ids = set(
        pd.read_csv(wavlm_split_path).loc[
            lambda split: split["split"].eq("test"), "voice_id"
        ].astype(str)
    )
    same_test_recordings = actual_test_ids == wavlm_test_ids
    same_wavlm_class_order = False
    if WAVLM_CHECKPOINT_PATH.is_file():
        wavlm_checkpoint = torch.load(
            WAVLM_CHECKPOINT_PATH, map_location="cpu", weights_only=True
        )
        same_wavlm_class_order = (
            wavlm_checkpoint.get("classes") == list(SOURCE_CLASS_NAMES)
        )
    metrics = {
        "model": "Custom Voice 1D CNN",
        "model_type": "scratch-trained three-class classification",
        "pretrained": False,
        "device": str(device),
        "class_order": list(CLASS_NAMES),
        "source_class_order": list(SOURCE_CLASS_NAMES),
        "training_accuracy": outputs["train"]["accuracy"],
        "validation_accuracy": outputs["validation"]["accuracy"],
        "test": test_metrics,
        "test_accuracy_percent": test_metrics["accuracy"] * 100,
        "training_loss": outputs["train"]["loss"],
        "validation_loss": outputs["validation"]["loss"],
        "test_loss": outputs["test"]["loss"],
        "best_epoch": int(checkpoint["best_epoch"]),
        "epochs_completed": int(checkpoint["epochs_completed"]),
        "early_stopping_occurred": bool(checkpoint["early_stopping_occurred"]),
        "parameters": config["architecture"]["parameters"],
        "majority_class_baseline": {
            "class": CLASS_NAMES[training_majority_index],
            "accuracy": test_metrics["majority_class_baseline_accuracy"],
            "accuracy_percent": test_metrics["majority_class_baseline_accuracy"] * 100,
        },
        "partitions": checkpoint["partitions"],
        "test_predictions_only_one_category": test_metrics["predicts_only_one_class"],
        "comparison_to_wavlm": {
            "wavlm_test_accuracy_percent": WAVLM_TEST_ACCURACY * 100,
            "same_test_recordings": same_test_recordings,
            "same_source_class_order": same_wavlm_class_order,
            "class_definition": "Weak->Developing mapping confirmed by user; same 3 source classes",
            "comparable": same_test_recordings and same_wavlm_class_order,
            "test_recordings": len(actual_test_ids),
        },
        "target_test_recordings": outputs["test"]["voice_ids"],
        "predictions": outputs["test"]["predictions"],
        "probabilities": outputs["test"]["probabilities"],
        "true_labels": outputs["test"]["labels"],
    }
    return metrics


def write_evaluation_outputs(
    metrics: dict[str, Any],
    result_dir: Path = RESULT_DIR,
) -> None:
    result_dir.mkdir(parents=True, exist_ok=True)
    serializable = dict(metrics)
    for key in ("predictions", "probabilities", "true_labels"):
        serializable.pop(key, None)
    (result_dir / "cnn_classification_metrics.json").write_text(
        json.dumps(serializable, indent=2, ensure_ascii=True) + "\n",
        encoding="utf-8",
    )

    test_report = metrics["test"]["classification_report"]
    report_rows: list[dict[str, Any]] = []
    for class_name in CLASS_NAMES:
        report_rows.append({"class": class_name, **test_report[class_name]})
    for aggregate in ("macro avg", "weighted avg"):
        report_rows.append({"class": aggregate, **test_report[aggregate]})
    report_rows.append(
        {
            "class": "accuracy",
            "precision": None,
            "recall": None,
            "f1-score": test_report["accuracy"],
            "support": metrics["test"]["total_samples"],
        }
    )
    pd.DataFrame(
        report_rows,
        columns=["class", "precision", "recall", "f1-score", "support"],
    ).to_csv(result_dir / "cnn_classification_report.csv", index=False)

    prediction_frame = pd.DataFrame(
        {
            "voice_id": metrics["target_test_recordings"],
            "true_class": [
                CLASS_NAMES[index] for index in metrics["true_labels"]
            ],
            "predicted_class": [
                CLASS_NAMES[index] for index in metrics["predictions"]
            ],
            "correct": [
                true == predicted
                for true, predicted in zip(
                    metrics["true_labels"], metrics["predictions"]
                )
            ],
        }
    )
    for index, class_name in enumerate(CLASS_NAMES):
        prediction_frame[f"probability_{class_name.casefold()}"] = [
            row[index] for row in metrics["probabilities"]
        ]
    prediction_frame.to_csv(
        result_dir / "cnn_test_predictions.csv", index=False
    )

    matrix = np.asarray(metrics["test"]["confusion_matrix"], dtype=np.int64)
    fig, ax = plt.subplots(figsize=(6, 5))
    image = ax.imshow(matrix, cmap="Blues")
    ax.set(
        xticks=range(len(CLASS_NAMES)),
        yticks=range(len(CLASS_NAMES)),
        xticklabels=CLASS_NAMES,
        yticklabels=CLASS_NAMES,
        xlabel="Predicted class",
        ylabel="Teacher class",
        title="Custom Voice 1D CNN Confusion Matrix",
    )
    for row in range(matrix.shape[0]):
        for column in range(matrix.shape[1]):
            ax.text(column, row, str(matrix[row, column]), ha="center", va="center")
    fig.colorbar(image, ax=ax)
    fig.tight_layout()
    fig.savefig(result_dir / "cnn_confusion_matrix.png", dpi=160)
    plt.close(fig)
