"""Shared paths and label/audio matching helpers for the voice pipeline."""

from __future__ import annotations

import hashlib
import re
from pathlib import Path
from typing import Any

import pandas as pd


AI_SERVICE_ROOT = Path(__file__).resolve().parents[2]
VOICE_ROOT = AI_SERVICE_ROOT / "data" / "voice"
RAW_ROOT = VOICE_ROOT / "raw"
PROCESSED_ROOT = VOICE_ROOT / "processed"
LABELS_ROOT = VOICE_ROOT / "labels"
SPLITS_ROOT = VOICE_ROOT / "splits"
MODEL_ROOT = AI_SERVICE_ROOT / "models" / "voice_xgboost"
RESULT_ROOT = AI_SERVICE_ROOT / "results" / "voice_xgboost"
LEGACY_LABELS_ROOT = AI_SERVICE_ROOT / "data" / "labels"
LABEL_SHEET = "VC01_Labels"
SUPPORTED_AUDIO = {".wav", ".mp3", ".m4a", ".ogg", ".flac", ".aac", ".mp4"}
EXCLUDED_DIRS = {"processed", "labels", "splits"}
VALIDATION_COLUMNS = (
    ("teacher_verification", {"teacher verified"}),
    ("label_status", {"validated"}),
    ("participant_identity_verified", {"yes", "true", "verified"}),
)


def normalize_column(value: Any) -> str:
    normalized = re.sub(r"[\s\-/]+", "_", str(value).strip().casefold())
    return re.sub(r"_+", "_", normalized).strip("_")


def normalized_id(value: Any) -> str:
    """Normalize V### and VC### identifiers to V###."""
    if pd.isna(value):
        return ""
    text = str(value).strip()
    match = re.search(r"(?i)(?:^|[^a-z0-9])v?c?0*(\d+)", Path(text).stem)
    if not match:
        match = re.match(r"(?i)v?c?0*(\d+)", Path(text).stem)
    if not match:
        return ""
    return f"V{int(match.group(1)):03d}"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def audio_root() -> Path:
    if RAW_ROOT.is_dir() and any(
        path.is_file() and path.suffix.casefold() in SUPPORTED_AUDIO
        for path in RAW_ROOT.rglob("*")
    ):
        return RAW_ROOT
    return VOICE_ROOT


def discover_audio(root: Path | None = None) -> list[Path]:
    search_root = root or audio_root()
    return sorted(
        (
            path
            for path in search_root.rglob("*")
            if path.is_file()
            and path.suffix.casefold() in SUPPORTED_AUDIO
            and not any(part.casefold() in EXCLUDED_DIRS for part in path.relative_to(VOICE_ROOT).parts)
        ),
        key=lambda path: str(path).casefold(),
    )


def find_label_workbook(explicit: Path | None = None) -> Path:
    candidates = [explicit.resolve()] if explicit else [
        *sorted(LABELS_ROOT.glob("*.xlsx"), key=lambda path: path.name.casefold()),
        *sorted(LEGACY_LABELS_ROOT.glob("*.xlsx"), key=lambda path: path.name.casefold()),
    ]
    errors: list[str] = []
    for path in candidates:
        if not path.is_file():
            continue
        try:
            if LABEL_SHEET in pd.ExcelFile(path).sheet_names:
                return path
        except (OSError, ValueError, ImportError) as exc:
            errors.append(f"{path}: {exc}")
    details = f" Workbook read errors: {errors}" if errors else ""
    raise FileNotFoundError(
        f"No workbook with sheet {LABEL_SHEET!r} found under "
        f"{LABELS_ROOT} or {LEGACY_LABELS_ROOT}.{details}"
    )


def load_labels(workbook: Path | None = None) -> tuple[pd.DataFrame, Path]:
    path = find_label_workbook(workbook)
    frame = pd.read_excel(path, sheet_name=LABEL_SHEET)
    frame.columns = [str(column).strip() for column in frame.columns]
    return frame, path


def column_lookup(frame: pd.DataFrame, *names: str) -> str | None:
    columns = {normalize_column(column): column for column in frame.columns}
    for name in names:
        found = columns.get(normalize_column(name))
        if found is not None:
            return found
    return None


def validation_mask(frame: pd.DataFrame) -> pd.Series:
    result = pd.Series(True, index=frame.index)
    for column_name, accepted in VALIDATION_COLUMNS:
        column = column_lookup(frame, column_name)
        if column is not None:
            result &= (
                frame[column]
                .fillna("")
                .astype(str)
                .str.strip()
                .str.casefold()
                .isin(accepted)
            )
        else:
            result &= False
    return result


def match_audio_by_id(
    labels: pd.DataFrame, audio_files: list[Path]
) -> tuple[pd.DataFrame, list[str]]:
    """Match by explicit filename first, then normalized V###/VC### ID.

    Numeric-ID fallback is exploratory only: callers must disclose that this
    assumes the Excel voice identifier and audio basename refer to the same
    participant. If multiple same-ID files exist, prefer the exact VC### stem.
    """
    voice_column = column_lookup(labels, "voice_id", "voice id")
    filename_column = column_lookup(labels, "filename", "file_name", "audio_filename")
    student_column = column_lookup(labels, "student_id", "student id")
    activity_column = column_lookup(labels, "activity_id", "activity id", "activity")
    if voice_column is None:
        raise ValueError("Teacher workbook is missing a voice_id column.")

    by_name: dict[str, list[Path]] = {}
    by_id: dict[str, list[Path]] = {}
    for path in audio_files:
        by_name.setdefault(path.name.casefold(), []).append(path)
        by_id.setdefault(normalized_id(path.name), []).append(path)

    rows: list[dict[str, Any]] = []
    warnings: list[str] = []
    for index, row in labels.iterrows():
        voice_id = str(row[voice_column]).strip() if pd.notna(row[voice_column]) else ""
        declared_name = (
            str(row[filename_column]).strip()
            if filename_column is not None and pd.notna(row[filename_column])
            else ""
        )
        candidates = by_name.get(Path(declared_name.replace("\\", "/")).name.casefold(), [])
        method = "filename"
        if len(candidates) != 1:
            candidates = by_id.get(normalized_id(voice_id), [])
            method = "voice_id_numeric_assumption"

        chosen: Path | None = None
        if len(candidates) == 1:
            chosen = candidates[0]
        elif len(candidates) > 1:
            canonical_stem = normalized_id(voice_id).replace("V", "VC")
            exact = [
                path for path in candidates
                if path.stem.casefold() == canonical_stem.casefold()
            ]
            if len(exact) == 1:
                chosen = exact[0]
            else:
                warnings.append(
                    f"Ambiguous audio for {voice_id}: "
                    + ", ".join(path.name for path in candidates)
                )

        rows.append({
            "label_row": int(index),
            "voice_id": voice_id,
            "student_id": str(row[student_column]).strip() if student_column and pd.notna(row[student_column]) else "",
            "activity_id": str(row[activity_column]).strip() if activity_column and pd.notna(row[activity_column]) else "",
            "declared_filename": declared_name,
            "audio_path": str(chosen.resolve()) if chosen else "",
            "match_method": method if chosen else "unmatched",
            "declared_filename_matches": bool(
                chosen and declared_name and chosen.name.casefold()
                == Path(declared_name.replace("\\", "/")).name.casefold()
            ),
        })
        if chosen and method == "voice_id_numeric_assumption":
            warnings.append(
                f"{voice_id}: matched to {chosen.name} by numeric ID, not declared filename."
            )
    return pd.DataFrame(rows), warnings


def target_columns(frame: pd.DataFrame) -> list[str]:
    return [
        column for column in frame.columns
        if normalize_column(column).endswith("_1_5")
    ]


def target_category(column: str) -> str:
    semantic = {
        "originality_1_5",
        "elaboration_1_5",
        "imagination_1_5",
        "narrative_coherence_1_5",
        "story_creativity_mean",
    }
    return "semantic_storytelling" if normalize_column(column) in semantic else "acoustic_voice"
