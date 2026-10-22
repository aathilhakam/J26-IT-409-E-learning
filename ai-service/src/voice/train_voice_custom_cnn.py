"""Custom 1D CNN architecture and grouped voice dataset loader.

Run the non-training verification first:
    python src/voice/train_voice_custom_cnn.py --check-architecture

This stage deliberately does not train weights or write a model checkpoint.
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import librosa
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import soundfile as sf
import torch
from torch import Tensor, nn
from torch.utils.data import DataLoader, Dataset
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import GroupKFold

if __package__:
    from .voice_common import MODEL_ROOT, RESULT_ROOT, SPLITS_ROOT, target_category
else:
    from voice_common import MODEL_ROOT, RESULT_ROOT, SPLITS_ROOT, target_category


SPLIT_PATH = SPLITS_ROOT / "voice_split.csv"
SAMPLE_RATE = 16_000
N_MELS = 64
N_FFT = 1024
HOP_LENGTH = 256
SEGMENT_SECONDS = 10.0
MIN_SCORE = 1.0
MAX_SCORE = 5.0
SEED = 42
MAX_EPOCHS = 50
BATCH_SIZE = 8
LEARNING_RATE = 0.001
WEIGHT_DECAY = 0.0001
EARLY_STOPPING_PATIENCE = 8
DEFAULT_CV_FOLDS = 3
CHECKPOINT_PATH = MODEL_ROOT / "best_voice_custom_cnn.pth"
METRICS_PATH = RESULT_ROOT / "voice_custom_cnn_metrics.json"
PREDICTIONS_PATH = RESULT_ROOT / "voice_custom_cnn_predictions.csv"
HISTORY_PATH = RESULT_ROOT / "voice_custom_cnn_training_history.csv"
LOSS_PLOT_PATH = RESULT_ROOT / "voice_custom_cnn_loss.png"
CONFIG_PATH = RESULT_ROOT / "voice_custom_cnn_config.json"
CV_PATH = RESULT_ROOT / "voice_custom_cnn_group_cv.csv"

IDENTIFIER_COLUMNS = {
    "voice_id",
    "student_id",
    "activity_id",
    "declared_filename",
    "source_filename",
    "processed_path",
    "audio_sha256",
    "match_method",
    "declared_filename_matches",
    "teacher_verified",
    "creativity_class",
    "split",
}


def discover_targets(frame: pd.DataFrame) -> list[str]:
    """Preserve workbook order for numeric rubric targets in the split."""
    targets = [
        str(column)
        for column in frame.columns
        if str(column).strip().casefold().endswith("_1_5")
    ]
    if not targets:
        raise ValueError(
            "The existing voice split contains no teacher-assessed *_1_5 targets."
        )
    return targets


def discover_acoustic_feature_columns(frame: pd.DataFrame) -> list[str]:
    """Return numeric handcrafted features, excluding all IDs and labels."""
    excluded = IDENTIFIER_COLUMNS | set(discover_targets(frame))
    return [
        column
        for column in frame.select_dtypes(include=[np.number]).columns
        if column not in excluded
    ]


def load_split_frame(split_path: Path = SPLIT_PATH) -> tuple[pd.DataFrame, list[str]]:
    """Load only verified rows with usable paths, IDs, and all target scores."""
    if not split_path.is_file():
        raise FileNotFoundError(
            f"Existing student-aware split not found: {split_path}. "
            "Run the voice dataset inspection and split pipeline first."
        )
    frame = pd.read_csv(split_path)
    required = {"student_id", "processed_path", "teacher_verified", "split"}
    missing = sorted(required - set(frame.columns))
    if missing:
        raise ValueError(f"Voice split is missing required columns: {missing}")
    targets = discover_targets(frame)
    missing_targets = [target for target in targets if target not in frame.columns]
    if missing_targets:
        raise ValueError(f"Target columns are missing from split: {missing_targets}")

    verified = frame["teacher_verified"].astype(str).str.strip().str.casefold().isin(
        {"true", "1", "yes"}
    )
    eligible = frame.loc[
        verified & frame["split"].isin({"train", "validation", "test"})
    ].copy()
    if eligible.empty:
        raise ValueError("No verified train/validation/test recordings were found.")
    eligible["student_id"] = eligible["student_id"].fillna("").astype(str).str.strip()
    if eligible["student_id"].eq("").any():
        raise ValueError("Some split records are missing student IDs.")
    eligible["processed_path"] = eligible["processed_path"].fillna("").astype(str)
    missing_audio = [
        path for path in eligible["processed_path"]
        if not path or not Path(path).is_file()
    ]
    if missing_audio:
        raise FileNotFoundError(
            f"{len(missing_audio)} processed audio paths are missing; for example: "
            f"{missing_audio[0]}. Re-run the non-destructive voice preprocessing."
        )
    for target in targets:
        eligible[target] = pd.to_numeric(eligible[target], errors="coerce")
    bad_scores = (
        eligible[targets].isna().any(axis=1)
        | ~eligible[targets].apply(
            lambda column: column.between(MIN_SCORE, MAX_SCORE)
        ).all(axis=1)
    )
    if bad_scores.any():
        bad_ids = eligible.loc[bad_scores, "voice_id"].astype(str).tolist()
        raise ValueError(
            "Missing or invalid teacher scores in split records: "
            + ", ".join(bad_ids)
        )

    split_students = {
        split: set(
            eligible.loc[eligible["split"].eq(split), "student_id"].astype(str)
        )
        for split in ("train", "validation", "test")
    }
    partitions = list(split_students)
    for index, left in enumerate(partitions):
        for right in partitions[index + 1 :]:
            overlap = split_students[left] & split_students[right]
            if overlap:
                raise ValueError(
                    f"Student leakage between {left} and {right}: {sorted(overlap)}"
                )
    if "audio_sha256" in eligible.columns:
        duplicate_audio = eligible["audio_sha256"].duplicated(keep=False)
        if duplicate_audio.any():
            ids = eligible.loc[duplicate_audio, "voice_id"].astype(str).tolist()
            raise ValueError(
                "Duplicate waveform hashes remain in the split; refusing to "
                f"reuse them across CNN partitions: {ids}"
            )
    return eligible.reset_index(drop=True), targets


@dataclass(frozen=True)
class MelConfig:
    sample_rate: int = SAMPLE_RATE
    n_mels: int = N_MELS
    n_fft: int = N_FFT
    hop_length: int = HOP_LENGTH
    segment_seconds: float = SEGMENT_SECONDS
    db_floor: float = -80.0


def audio_to_logmel_segments(
    audio_path: Path,
    config: MelConfig = MelConfig(),
) -> tuple[list[np.ndarray], list[int], float]:
    """Create non-overlapping fixed-window log-mel segments from one recording.

    Log-mel power is expressed in dB relative to 1.0 and floored at -80 dB.
    It is not peak-normalized, preserving recording-level vocal dynamics.
    Every segment remains attached to one recording/student and receives one
    recording-level target vector.
    """
    if not audio_path.is_file():
        raise FileNotFoundError(f"Recording does not exist: {audio_path}")
    try:
        info = sf.info(audio_path)
    except (OSError, RuntimeError) as exc:
        raise ValueError(f"Audio is unreadable or corrupt: {audio_path}") from exc
    if info.frames <= 0 or info.samplerate <= 0:
        raise ValueError(f"Audio is empty or has an invalid sample rate: {audio_path}")
    try:
        audio, sample_rate = librosa.load(
            str(audio_path), sr=config.sample_rate, mono=True, dtype=np.float32
        )
    except Exception as exc:
        raise ValueError(f"Could not decode audio recording {audio_path}: {exc}") from exc
    if audio.size == 0 or not np.isfinite(audio).all():
        raise ValueError(f"Audio is empty or contains invalid samples: {audio_path}")

    duration = len(audio) / sample_rate
    samples_per_segment = max(1, int(config.segment_seconds * sample_rate))
    segments: list[np.ndarray] = []
    valid_frame_lengths: list[int] = []
    max_frames = math.ceil(samples_per_segment / config.hop_length) + 1
    mel_filter = librosa.filters.mel(
        sr=sample_rate,
        n_fft=config.n_fft,
        n_mels=config.n_mels,
    )
    for start in range(0, len(audio), samples_per_segment):
        chunk = audio[start : start + samples_per_segment]
        power = np.abs(
            librosa.stft(
                chunk,
                n_fft=config.n_fft,
                hop_length=config.hop_length,
                center=True,
            )
        ) ** 2
        mel_power = mel_filter @ power
        logmel = librosa.power_to_db(mel_power, ref=1.0, top_db=None)
        logmel = np.maximum(logmel, config.db_floor).astype(np.float32)
        valid_frames = int(logmel.shape[1])
        padded = np.zeros((config.n_mels, max_frames), dtype=np.float32)
        padded[:, :valid_frames] = logmel
        segments.append(padded)
        valid_frame_lengths.append(valid_frames)
    return segments, valid_frame_lengths, float(duration)


class VoiceLogMelDataset(Dataset[dict[str, Any]]):
    """Recording-level dataset; chunks are collated and pooled per recording."""

    def __init__(
        self,
        frame: pd.DataFrame,
        targets: list[str],
        split: str,
        config: MelConfig = MelConfig(),
        mean: Tensor | None = None,
        std: Tensor | None = None,
    ) -> None:
        if split not in {"train", "validation", "test"}:
            raise ValueError(f"Unknown dataset split: {split}")
        self.frame = frame.loc[frame["split"].eq(split)].reset_index(drop=True)
        if self.frame.empty:
            raise ValueError(f"The {split} split contains no recordings.")
        self.targets = targets
        self.config = config
        if (mean is None) != (std is None):
            raise ValueError("Both training mean and standard deviation must be set together.")
        self.mean = mean
        self.std = std
        self._cache: dict[int, tuple[list[np.ndarray], list[int], float]] = {}

    def __len__(self) -> int:
        return len(self.frame)

    def _features(self, index: int) -> tuple[list[np.ndarray], list[int], float]:
        if index not in self._cache:
            path = Path(self.frame.iloc[index]["processed_path"])
            self._cache[index] = audio_to_logmel_segments(path, self.config)
        return self._cache[index]

    def __getitem__(self, index: int) -> dict[str, Any]:
        segments, lengths, duration = self._features(index)
        values = np.stack(segments)
        if self.mean is not None and self.std is not None:
            mean = self.mean.detach().cpu().numpy()[:, None]
            std = self.std.detach().cpu().numpy()[:, None]
            values = (values - mean) / std
        row = self.frame.iloc[index]
        return {
            "logmel": torch.from_numpy(values),
            "frame_lengths": torch.tensor(lengths, dtype=torch.long),
            "targets": torch.tensor(
                row[self.targets].to_numpy(dtype=np.float32),
                dtype=torch.float32,
            ),
            "voice_id": str(row.get("voice_id", "")),
            "student_id": str(row["student_id"]),
            "activity_id": str(row.get("activity_id", "")),
            "duration_seconds": duration,
            "processed_path": str(row["processed_path"]),
        }


def collate_recordings(records: list[dict[str, Any]]) -> dict[str, Any]:
    """Pad segment count, retaining per-segment valid frame lengths."""
    if not records:
        raise ValueError("Cannot collate an empty recording batch.")
    max_segments = max(record["logmel"].shape[0] for record in records)
    n_mels = records[0]["logmel"].shape[1]
    time_steps = records[0]["logmel"].shape[2]
    batch_size = len(records)
    values = torch.zeros(
        (batch_size, max_segments, n_mels, time_steps), dtype=torch.float32
    )
    lengths = torch.zeros((batch_size, max_segments), dtype=torch.long)
    for batch_index, record in enumerate(records):
        segment_count = record["logmel"].shape[0]
        values[batch_index, :segment_count] = record["logmel"]
        lengths[batch_index, :segment_count] = record["frame_lengths"]
    return {
        "logmel": values,
        "frame_lengths": lengths,
        "targets": torch.stack([record["targets"] for record in records]),
        "voice_id": [record["voice_id"] for record in records],
        "student_id": [record["student_id"] for record in records],
        "activity_id": [record["activity_id"] for record in records],
        "duration_seconds": [record["duration_seconds"] for record in records],
        "processed_path": [record["processed_path"] for record in records],
    }


def compute_training_statistics(
    dataset: VoiceLogMelDataset,
) -> tuple[Tensor, Tensor]:
    """Compute per-mel mean/std from valid frames in train recordings only."""
    if not dataset.frame["split"].eq("train").all():
        raise ValueError("Feature normalization may only use training recordings.")
    total = np.zeros(dataset.config.n_mels, dtype=np.float64)
    total_squared = np.zeros(dataset.config.n_mels, dtype=np.float64)
    frame_count = 0
    for index in range(len(dataset)):
        segments, lengths, _ = dataset._features(index)
        for segment, valid_frames in zip(segments, lengths):
            valid = segment[:, :valid_frames].astype(np.float64, copy=False)
            total += valid.sum(axis=1)
            total_squared += np.square(valid).sum(axis=1)
            frame_count += valid.shape[1]
    if frame_count == 0:
        raise ValueError("No valid log-mel frames were found in training audio.")
    mean = total / frame_count
    variance = np.maximum(total_squared / frame_count - mean**2, 1e-8)
    std = np.sqrt(variance)
    return torch.from_numpy(mean.astype(np.float32)), torch.from_numpy(
        std.astype(np.float32)
    )


class CustomVoiceCNN1D(nn.Module):
    """Three-block 1D CNN with length-aware segment and recording pooling."""

    def __init__(self, n_targets: int) -> None:
        super().__init__()
        if n_targets < 1:
            raise ValueError("The model requires at least one validated target.")
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
            nn.Linear(128, n_targets),
        )
        self.output_dim = n_targets
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

    @staticmethod
    def _mask_after_pool(values: Tensor, lengths: Tensor) -> Tensor:
        time_index = torch.arange(values.shape[-1], device=values.device)
        mask = time_index.unsqueeze(0) < lengths.unsqueeze(1)
        return values * mask.unsqueeze(1).to(values.dtype)

    def _forward_segments(self, logmel: Tensor, lengths: Tensor) -> Tensor:
        if logmel.ndim != 3 or logmel.shape[1] != 64:
            raise ValueError(
                "CNN input must have shape [segments, 64, time_steps]."
            )
        lengths = lengths.to(device=logmel.device, dtype=torch.long)
        if torch.any(lengths <= 0) or torch.any(lengths > logmel.shape[-1]):
            raise ValueError("Every segment must have a valid positive frame length.")

        x = self.block1[0](logmel)
        x = self.block1[1](x)
        x = self.block1[2](x)
        x = self.block1[3](x)
        lengths = torch.div(lengths, 2, rounding_mode="floor").clamp(min=1)
        x = self._mask_after_pool(x, lengths)

        x = self.block2[0](x)
        x = self.block2[1](x)
        x = self.block2[2](x)
        x = self.block2[3](x)
        lengths = torch.div(lengths, 2, rounding_mode="floor").clamp(min=1)
        x = self._mask_after_pool(x, lengths)

        x = self.block3[0](x)
        x = self.block3[1](x)
        x = self.block3[2](x)
        x = self.block3[3](x)
        lengths = torch.div(lengths, 2, rounding_mode="floor").clamp(min=1)
        x = self._mask_after_pool(x, lengths)

        time_index = torch.arange(x.shape[-1], device=x.device)
        mask = (time_index.unsqueeze(0) < lengths.unsqueeze(1)).unsqueeze(1)
        pooled = (x * mask.to(x.dtype)).sum(dim=-1) / lengths.unsqueeze(1).to(x.dtype)
        return self.head(pooled)

    def forward(self, logmel: Tensor, frame_lengths: Tensor) -> Tensor:
        """Return one raw regression score vector per recording.

        Inputs may be [B, 64, T] for one segment per recording or
        [B, S, 64, T] for multiple segments. Segment outputs are averaged
        within a recording; padded segments are excluded. Callers clamp only
        for reporting/inference so training gradients are not saturated.
        """
        if logmel.ndim == 3:
            segment_lengths = frame_lengths.reshape(-1)
            segment_predictions = self._forward_segments(logmel, segment_lengths)
            return segment_predictions
        if logmel.ndim != 4 or frame_lengths.shape != logmel.shape[:2]:
            raise ValueError(
                "Recording input must be [B, S, 64, T] with lengths [B, S]."
            )
        batch_size, segment_count, channels, time_steps = logmel.shape
        flat_lengths = frame_lengths.reshape(-1)
        valid = flat_lengths > 0
        if not torch.any(valid):
            raise ValueError("The batch contains no valid audio segments.")
        flat_logmel = logmel.reshape(-1, channels, time_steps)[valid]
        segment_predictions = self._forward_segments(
            flat_logmel, flat_lengths[valid]
        )
        all_predictions = segment_predictions.new_zeros(
            (batch_size * segment_count, self.output_dim)
        )
        all_predictions[valid] = segment_predictions
        all_predictions = all_predictions.reshape(
            batch_size, segment_count, self.output_dim
        )
        weights = valid.reshape(batch_size, segment_count).to(
            segment_predictions.dtype
        )
        recording_predictions = (
            all_predictions * weights.unsqueeze(-1)
        ).sum(dim=1) / weights.sum(dim=1, keepdim=True)
        return recording_predictions


def describe_model(model: CustomVoiceCNN1D, targets: list[str]) -> None:
    print(model)
    conv_layers = [layer for layer in model.modules() if isinstance(layer, nn.Conv1d)]
    trainable = sum(parameter.numel() for parameter in model.parameters() if parameter.requires_grad)
    total = sum(parameter.numel() for parameter in model.parameters())
    print(f"Convolutional layers: {len(conv_layers)}")
    print(
        "Filters: "
        + ", ".join(f"{layer.in_channels}->{layer.out_channels}" for layer in conv_layers)
    )
    print("Kernel sizes: " + ", ".join(str(layer.kernel_size[0]) for layer in conv_layers))
    print(f"Trainable parameters: {trainable:,}")
    print(f"Total parameters: {total:,}")
    print(f"Output dimensions: {model.output_dim} ({', '.join(targets)})")


def check_architecture(split_path: Path = SPLIT_PATH) -> int:
    torch.manual_seed(SEED)
    np.random.seed(SEED)
    frame, targets = load_split_frame(split_path)
    feature_columns = discover_acoustic_feature_columns(frame)
    model = CustomVoiceCNN1D(n_targets=len(targets))
    describe_model(model, targets)

    print("\nReused existing student-aware split:")
    for split in ("train", "validation", "test"):
        partition = frame.loc[frame["split"].eq(split)]
        print(
            f"  {split}: {len(partition)} recordings, "
            f"{partition['student_id'].nunique()} students"
        )
    print(f"Unique students: {frame['student_id'].nunique()}")
    print(f"Numeric handcrafted acoustic features present in split: {len(feature_columns)}")
    print(f"Log-mel channels: {N_MELS}; sample rate: {SAMPLE_RATE} Hz")
    print(
        f"FFT: {N_FFT}; hop: {HOP_LENGTH}; segment length: {SEGMENT_SECONDS:g}s"
    )

    training_dataset = VoiceLogMelDataset(frame, targets, "train")
    train_mean, train_std = compute_training_statistics(training_dataset)
    training_dataset.mean = train_mean
    training_dataset.std = train_std
    examples = [
        training_dataset[index]
        for index in range(min(2, len(training_dataset)))
    ]
    batch = collate_recordings(examples)
    model.eval()
    with torch.inference_mode():
        predictions = model(batch["logmel"], batch["frame_lengths"])
    expected_shape = (len(examples), len(targets))
    if tuple(predictions.shape) != expected_shape:
        raise RuntimeError(
            f"Unexpected CNN output shape {tuple(predictions.shape)}, "
            f"expected {expected_shape}."
        )
    score_values = batch["targets"]
    if torch.any(score_values < MIN_SCORE) or torch.any(score_values > MAX_SCORE):
        raise ValueError("The dataset loader returned a score outside the rubric range.")

    print("\nReal dataset loader check:")
    print(f"  voice_ids: {', '.join(batch['voice_id'])}")
    print(f"  student_ids: {', '.join(batch['student_id'])}")
    print(f"  activities: {', '.join(batch['activity_id'])}")
    print(
        "  durations: "
        + ", ".join(f"{duration:.2f}s" for duration in batch["duration_seconds"])
    )
    print(
        f"  log-mel tensor: {tuple(batch['logmel'].shape)} "
        "(batch, segments, 64 mel bins, frames)"
    )
    print(
        f"  padding-aware valid segments: "
        f"{int((batch['frame_lengths'] > 0).sum())}/"
        f"{batch['frame_lengths'].numel()}"
    )
    print(
        f"  training-only normalization: {train_mean.numel()} mel-band means/stds; "
        "computed from train recordings only"
    )
    print(f"  target tensor: {tuple(batch['targets'].shape)}; score range 1-5")
    print(f"  CNN output tensor: {tuple(predictions.shape)}")
    print("  Original audio opened read-only; no training performed.")
    print("ARCHITECTURE AND DATASET CHECK: PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", type=Path, default=SPLIT_PATH)
    parser.add_argument(
        "--check-architecture",
        action="store_true",
        help="Validate model dimensions and load one real split recording; does not train.",
    )
    args = parser.parse_args()
    if not args.check_architecture:
        parser.error(
            "Training is intentionally disabled at this step. "
            "Run with --check-architecture; training will be implemented after confirmation."
        )
    return check_architecture(args.split)


if __name__ == "__main__":
    raise SystemExit(main())
