"""Extract handcrafted acoustic features; no pretrained models or embeddings."""

from __future__ import annotations

import argparse
from pathlib import Path

import librosa
import numpy as np
import pandas as pd

from voice_common import (
    PROCESSED_ROOT,
    VOICE_ROOT,
    column_lookup,
    discover_audio,
    load_labels,
    match_audio_by_id,
    target_columns,
    validation_mask,
)


METADATA_PATH = VOICE_ROOT / "voice_audio_metadata.csv"
FEATURES_PATH = VOICE_ROOT / "voice_features.csv"
SAMPLE_RATE = 16_000
N_MFCC = 13
HOP_LENGTH = 512
FRAME_LENGTH = 2048
SILENCE_FRAME_LENGTH = 400
SILENCE_HOP_LENGTH = 160
SILENCE_DBFS_THRESHOLD = -40.0
PAUSE_MIN_SECONDS = 0.25


def add_statistics(features: dict[str, float], prefix: str, values: np.ndarray) -> None:
    array = np.asarray(values, dtype=np.float64).reshape(-1)
    finite = array[np.isfinite(array)]
    if finite.size == 0:
        features[f"{prefix}_mean"] = np.nan
        features[f"{prefix}_std"] = np.nan
        return
    features[f"{prefix}_mean"] = float(np.mean(finite))
    features[f"{prefix}_std"] = float(np.std(finite))


def extract_features(audio_path: Path) -> dict[str, float]:
    """Calculate deterministic summary features from one processed mono WAV."""
    audio, sample_rate = librosa.load(
        str(audio_path), sr=SAMPLE_RATE, mono=True, dtype=np.float32
    )
    if audio.size == 0 or not np.isfinite(audio).all():
        raise ValueError(f"Empty or non-finite audio: {audio_path}")
    if sample_rate != SAMPLE_RATE:
        raise ValueError(f"Expected {SAMPLE_RATE} Hz audio: {audio_path}")

    result: dict[str, float] = {
        "speech_duration_seconds": float(len(audio) / sample_rate),
    }
    mfcc = librosa.feature.mfcc(
        y=audio, sr=sample_rate, n_mfcc=N_MFCC,
        n_fft=FRAME_LENGTH, hop_length=HOP_LENGTH,
    )
    delta_features = {
        "mfcc": mfcc,
        "mfcc_delta": None,
        "mfcc_delta2": None,
    }
    if mfcc.shape[1] >= 9:
        delta_features["mfcc_delta"] = librosa.feature.delta(mfcc, order=1)
        delta_features["mfcc_delta2"] = librosa.feature.delta(mfcc, order=2)
    for family, matrix in delta_features.items():
        for coefficient in range(N_MFCC):
            name = f"{family}_{coefficient + 1:02d}"
            if matrix is None:
                result[f"{name}_mean"] = np.nan
                result[f"{name}_std"] = np.nan
            else:
                add_statistics(result, name, matrix[coefficient])

    rms = librosa.feature.rms(
        y=audio, frame_length=FRAME_LENGTH, hop_length=HOP_LENGTH
    )[0]
    zcr = librosa.feature.zero_crossing_rate(
        audio, frame_length=FRAME_LENGTH, hop_length=HOP_LENGTH
    )[0]
    spectrum = np.abs(librosa.stft(
        audio, n_fft=FRAME_LENGTH, hop_length=HOP_LENGTH
    ))
    spectral_features = {
        "rms": rms,
        "zero_crossing_rate": zcr,
        "spectral_centroid": librosa.feature.spectral_centroid(
            S=spectrum, sr=sample_rate
        )[0],
        "spectral_bandwidth": librosa.feature.spectral_bandwidth(
            S=spectrum, sr=sample_rate
        )[0],
        "spectral_rolloff": librosa.feature.spectral_rolloff(
            S=spectrum, sr=sample_rate
        )[0],
        "spectral_flatness": librosa.feature.spectral_flatness(S=spectrum)[0],
    }
    for name, values in spectral_features.items():
        add_statistics(result, name, values)

    chroma = librosa.feature.chroma_stft(
        S=spectrum**2, sr=sample_rate, hop_length=HOP_LENGTH
    )
    for pitch_class in range(chroma.shape[0]):
        add_statistics(result, f"chroma_{pitch_class:02d}", chroma[pitch_class])

    try:
        pitch = librosa.yin(
            audio,
            fmin=65.0,
            fmax=500.0,
            sr=sample_rate,
            frame_length=FRAME_LENGTH,
            hop_length=HOP_LENGTH,
        )
        frame_energy = librosa.feature.rms(
            y=audio, frame_length=FRAME_LENGTH, hop_length=HOP_LENGTH
        )[0]
        count = min(len(pitch), len(frame_energy))
        voiced_threshold = max(1e-4, float(np.max(frame_energy[:count])) * 0.1)
        voiced_pitch = pitch[:count][frame_energy[:count] >= voiced_threshold]
        if voiced_pitch.size >= 5:
            add_statistics(result, "pitch_hz", voiced_pitch)
            result["pitch_hz_min"] = float(np.min(voiced_pitch))
            result["pitch_hz_max"] = float(np.max(voiced_pitch))
        else:
            result.update({
                "pitch_hz_mean": np.nan,
                "pitch_hz_std": np.nan,
                "pitch_hz_min": np.nan,
                "pitch_hz_max": np.nan,
            })
    except (librosa.util.exceptions.ParameterError, ValueError):
        result.update({
            "pitch_hz_mean": np.nan,
            "pitch_hz_std": np.nan,
            "pitch_hz_min": np.nan,
            "pitch_hz_max": np.nan,
        })

    # Pause proxy: 25 ms non-overlapping frames below -40 dBFS are silent;
    # consecutive silent frames are counted as pauses only at >=250 ms.
    frame_rms = librosa.feature.rms(
        y=audio,
        frame_length=SILENCE_FRAME_LENGTH,
        hop_length=SILENCE_HOP_LENGTH,
        center=False,
    )[0]
    threshold = 10.0 ** (SILENCE_DBFS_THRESHOLD / 20.0)
    silent = frame_rms < threshold
    frame_seconds = SILENCE_HOP_LENGTH / sample_rate
    transitions = np.diff(np.r_[False, silent, False].astype(np.int8))
    starts = np.flatnonzero(transitions == 1)
    ends = np.flatnonzero(transitions == -1)
    pause_durations = (ends - starts) * frame_seconds
    accepted_pauses = pause_durations[pause_durations >= PAUSE_MIN_SECONDS]
    result["silence_ratio"] = float(np.mean(silent)) if silent.size else np.nan
    result["pause_count"] = float(accepted_pauses.size)
    result["mean_pause_seconds"] = (
        float(np.mean(accepted_pauses)) if accepted_pauses.size else 0.0
    )
    result["max_pause_seconds"] = (
        float(np.max(accepted_pauses)) if accepted_pauses.size else 0.0
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--labels", type=Path, default=None)
    parser.add_argument("--metadata", type=Path, default=METADATA_PATH)
    parser.add_argument("--output", type=Path, default=FEATURES_PATH)
    args = parser.parse_args()

    if not args.metadata.is_file():
        raise FileNotFoundError(
            f"Audio metadata is missing: {args.metadata}. "
            "Run preprocess_voice.py first."
        )
    metadata = pd.read_csv(args.metadata).fillna("")
    successful = metadata[metadata["status"].eq("processed")].copy()
    if successful.empty:
        raise ValueError("No successfully processed audio found in metadata.")

    labels, workbook = load_labels(args.labels)
    source_files = discover_audio()
    matches, warnings = match_audio_by_id(labels, source_files)
    source_metadata = successful.set_index("source_path", drop=False)
    targets = target_columns(labels)
    voice_column = column_lookup(labels, "voice_id", "voice id")
    verified = validation_mask(labels)
    rows: list[dict[str, object]] = []
    errors: list[str] = []

    for match in matches.itertuples(index=False):
        if not match.audio_path or match.audio_path not in source_metadata.index:
            continue
        label_row = labels.iloc[match.label_row]
        source = source_metadata.loc[match.audio_path]
        if isinstance(source, pd.DataFrame):
            source = source.iloc[0]
        record: dict[str, object] = {
            "voice_id": match.voice_id,
            "student_id": match.student_id,
            "activity_id": match.activity_id,
            "declared_filename": match.declared_filename,
            "source_filename": source["source_filename"],
            "processed_path": source["processed_path"],
            "audio_sha256": source["source_sha256"],
            "match_method": match.match_method,
            "declared_filename_matches": match.declared_filename_matches,
            "teacher_verified": bool(verified.iloc[match.label_row]),
            "creativity_class": (
                str(label_row["creativity class"]).strip()
                if "creativity class" in labels.columns
                and pd.notna(label_row["creativity class"])
                else ""
            ),
        }
        for target in targets:
            value = pd.to_numeric(
                pd.Series([label_row[target]]), errors="coerce"
            ).iloc[0]
            record[target] = (
                float(value) if pd.notna(value) and 1 <= value <= 5 else np.nan
            )
        try:
            record.update(extract_features(Path(source["processed_path"])))
            rows.append(record)
        except (OSError, RuntimeError, ValueError) as exc:
            errors.append(f"{match.voice_id}: {exc}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    feature_frame = pd.DataFrame(rows)
    feature_frame.to_csv(args.output, index=False, encoding="utf-8")
    print(f"Label workbook: {workbook}")
    print(f"Feature rows written: {len(feature_frame)}")
    print(f"Acoustic feature columns: {sum(column.startswith(('mfcc_', 'mfcc_delta', 'mfcc_delta2', 'rms_', 'zero_crossing_rate_', 'spectral_', 'chroma_', 'pitch_', 'speech_duration_', 'silence_', 'pause_')) for column in feature_frame.columns)}")
    print(f"Teacher-assessed target columns: {', '.join(targets)}")
    print(f"Features CSV: {args.output}")
    print("Student IDs, filenames, comments, and targets are metadata/labels only, not acoustic features.")
    print("Features are handcrafted; no pretrained encoders or embeddings were used.")
    if errors:
        print(f"Failed feature extractions: {len(errors)}")
        for error in errors:
            print(f"  - {error}")
    if warnings:
        print(f"ID-match notes: {len(warnings)}; numeric-ID fallback is exploratory.")
    return 0 if len(feature_frame) else 1


if __name__ == "__main__":
    raise SystemExit(main())
