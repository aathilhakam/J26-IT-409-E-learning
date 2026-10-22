"""Inspect voice recordings and teacher labels without modifying either."""

from __future__ import annotations

import argparse
import hashlib
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

import pandas as pd


AI_SERVICE_ROOT = Path(__file__).resolve().parents[2]
VOICE_ROOT = AI_SERVICE_ROOT / "data" / "voice"
SUPPORTED_AUDIO = {".wav", ".mp3", ".m4a", ".ogg", ".flac", ".aac", ".mp4"}
EXCLUDED_AUDIO_DIRS = {"processed", "labels", "splits"}
EXPECTED_LABEL_SHEET = "VC01_Labels"
SCORE_SUFFIX = "_1_5"


def normalize_column_name(value: Any) -> str:
    """Normalize a workbook header for matching without changing its display."""
    text = str(value).strip().casefold()
    text = re.sub(r"[\s\-/]+", "_", text)
    return re.sub(r"_+", "_", text).strip("_")


def normalize_identifier(value: Any) -> str:
    if pd.isna(value):
        return ""
    return str(value).strip().casefold()


def identifier_number(value: Any) -> str:
    """Return the numeric portion of V###, VC###, or a labeled audio filename."""
    text = normalize_identifier(value)
    if not text:
        return ""
    stem = Path(text.replace("\\", "/")).stem
    stem = re.sub(r"\s+copy(?:\s+\d+)?$", "", stem, flags=re.IGNORECASE)
    match = re.search(r"(?:^|[^a-z0-9])v?c?0*(\d+)", stem, flags=re.IGNORECASE)
    if not match:
        match = re.match(r"v?c?0*(\d+)", stem, flags=re.IGNORECASE)
    if not match:
        return ""
    return str(int(match.group(1)))


def audio_search_root(voice_root: Path) -> Path:
    """Prefer raw/ when populated; otherwise inspect the existing voice tree."""
    raw_root = voice_root / "raw"
    if raw_root.is_dir() and any(
        path.is_file() and path.suffix.casefold() in SUPPORTED_AUDIO
        for path in raw_root.rglob("*")
    ):
        return raw_root
    return voice_root


def discover_audio_files(search_root: Path, voice_root: Path) -> list[Path]:
    files = []
    for path in search_root.rglob("*"):
        if not path.is_file() or path.suffix.casefold() not in SUPPORTED_AUDIO:
            continue
        relative_parts = path.relative_to(voice_root).parts
        if any(part.casefold() in EXCLUDED_AUDIO_DIRS for part in relative_parts):
            continue
        files.append(path)
    return sorted(files, key=lambda item: str(item).casefold())


def find_label_workbook(explicit_path: Path | None) -> tuple[Path | None, list[str]]:
    """Find the workbook carrying the expected voice-label sheet."""
    if explicit_path is not None:
        path = explicit_path.expanduser().resolve()
        if not path.is_file():
            return None, [f"Requested workbook does not exist: {path}"]
        candidates = [path]
    else:
        search_dirs = [
            VOICE_ROOT / "labels",
            AI_SERVICE_ROOT / "data" / "labels",
        ]
        candidates = sorted(
            {
                path.resolve()
                for directory in search_dirs
                if directory.is_dir()
                for path in directory.glob("*.xlsx")
            },
            key=lambda item: str(item).casefold(),
        )

    workbook_errors = []
    for path in candidates:
        try:
            if EXPECTED_LABEL_SHEET in pd.ExcelFile(path).sheet_names:
                return path, workbook_errors
        except (OSError, ValueError, ImportError) as exc:
            workbook_errors.append(f"{path.name}: {exc}")
    return (candidates[0] if candidates else None), workbook_errors


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_audio_candidates(
    voice_id: Any, audio_by_number: dict[str, list[Path]]
) -> list[Path]:
    number = identifier_number(voice_id)
    candidates = audio_by_number.get(number, []) if number else []
    if not candidates:
        return []

    canonical_stems = {
        f"vc{int(number):03d}".casefold(),
        f"v{int(number):03d}".casefold(),
    }
    canonical = [
        path for path in candidates if path.stem.casefold() in canonical_stems
    ]
    return canonical if canonical else candidates


def find_column(frame: pd.DataFrame, *names: str) -> str | None:
    lookup = {normalize_column_name(column): column for column in frame.columns}
    for name in names:
        if normalize_column_name(name) in lookup:
            return lookup[normalize_column_name(name)]
    return None


def format_items(items: list[str], limit: int = 20) -> str:
    if not items:
        return "None"
    shown = items[:limit]
    suffix = f" ... and {len(items) - limit} more" if len(items) > limit else ""
    return ", ".join(shown) + suffix


def inspect_dataset(
    voice_root: Path,
    labels_path: Path | None,
    sheet_name: str,
) -> int:
    voice_root = voice_root.expanduser().resolve()
    search_root = audio_search_root(voice_root)
    audio_files = discover_audio_files(search_root, voice_root)
    workbook, workbook_errors = find_label_workbook(labels_path)

    print("VOICE DATASET INSPECTION (read-only)")
    print(f"Voice directory: {voice_root}")
    print(f"Audio search directory: {search_root}")
    print(f"Supported audio files found: {len(audio_files)}")
    if not audio_files:
        print("  No WAV/MP3/M4A/OGG/FLAC/AAC/MP4 recordings found.")
        print(f"  Copy original recordings into: {voice_root / 'raw'}")

    if workbook_errors:
        print("\nWorkbook discovery/read warnings:")
        for warning in workbook_errors:
            print(f"  - {warning}")
    if workbook is None or not workbook.is_file():
        print("\nLABEL WORKBOOK: MISSING")
        print("  Expected a workbook with a VC01_Labels sheet.")
        print(f"  Copy the teacher workbook into: {voice_root / 'labels'}")
        print(f"  Legacy search location checked: {AI_SERVICE_ROOT / 'data' / 'labels'}")
        return 1

    print(f"\nLabel workbook: {workbook}")
    try:
        excel = pd.ExcelFile(workbook)
    except (OSError, ValueError, ImportError) as exc:
        print(f"  Could not read workbook: {exc}")
        return 1

    print("Workbook sheets: " + ", ".join(excel.sheet_names))
    if sheet_name not in excel.sheet_names:
        print(f"  Required sheet not found: {sheet_name}")
        return 1

    try:
        labels = pd.read_excel(workbook, sheet_name=sheet_name)
    except (OSError, ValueError, ImportError) as exc:
        print(f"  Could not read sheet {sheet_name}: {exc}")
        return 1

    print(f"Label sheet: {sheet_name} ({len(labels)} rows)")
    print("\nComplete workbook column list:")
    for column in labels.columns:
        print(f"  - {column}")

    voice_id_col = find_column(labels, "voice_id", "voice id")
    student_id_col = find_column(labels, "student_id", "student id")
    filename_col = find_column(labels, "filename", "file_name", "audio_filename")
    activity_col = find_column(labels, "activity_id", "activity id", "activity")
    teacher_col = find_column(labels, "teacher_verification", "teacher verification")
    status_col = find_column(labels, "label_status", "label status")
    identity_col = find_column(
        labels, "participant_identity_verified", "participant identity verified"
    )

    if voice_id_col is None:
        print("\nCannot match labels: no voice_id column was found.")
        return 1

    score_columns = [
        column
        for column in labels.columns
        if normalize_column_name(column).endswith(SCORE_SUFFIX)
    ]
    verified_mask = pd.Series(True, index=labels.index)
    if teacher_col is not None:
        verified_mask &= (
            labels[teacher_col]
            .fillna("")
            .astype(str)
            .str.strip()
            .str.casefold()
            .eq("teacher verified")
        )
    if status_col is not None:
        verified_mask &= (
            labels[status_col]
            .fillna("")
            .astype(str)
            .str.strip()
            .str.casefold()
            .eq("validated")
        )
    if identity_col is not None:
        verified_mask &= (
            labels[identity_col]
            .fillna("")
            .astype(str)
            .str.strip()
            .str.casefold()
            .isin({"yes", "true", "verified"})
        )

    print("\nTeacher-assessed numeric score factors (valid score range: 1-5):")
    if not score_columns:
        print("  No columns ending in _1_5 were found.")
    for column in score_columns:
        numeric = pd.to_numeric(labels[column], errors="coerce")
        valid = numeric.between(1, 5)
        verified_valid = valid & verified_mask
        print(
            f"  {column}: valid 1-5={int(valid.sum())}/{len(labels)}, "
            f"valid on teacher-verified rows={int(verified_valid.sum())}"
        )

    if activity_col is not None:
        activities = sorted(
            {
                str(value).strip()
                for value in labels[activity_col].dropna()
                if str(value).strip()
            }
        )
        print("\nActivities in workbook: " + (", ".join(activities) or "None"))

    voice_ids = labels[voice_id_col].map(normalize_identifier)
    students = (
        labels[student_id_col].map(normalize_identifier)
        if student_id_col is not None
        else pd.Series("", index=labels.index)
    )
    duplicate_voice_ids = sorted(
        set(voice_ids[voice_ids.duplicated(keep=False)]) - {""}
    )
    duplicate_student_ids = sorted(
        set(students[students.duplicated(keep=False)]) - {""}
    )

    repeated_label_filenames = []
    if filename_col is not None:
        filename_values = labels[filename_col].map(normalize_identifier)
        repeated_label_filenames = [
            f"{name} ({count} rows)"
            for name, count in filename_values[filename_values.ne("")]
            .value_counts()
            .items()
            if count > 1
        ]

    audio_by_name: dict[str, list[Path]] = defaultdict(list)
    audio_by_number: dict[str, list[Path]] = defaultdict(list)
    for path in audio_files:
        audio_by_name[path.name.casefold()].append(path)
        number = identifier_number(path.name)
        if number:
            audio_by_number[number].append(path)

    matched_rows: list[tuple[str, str, str]] = []
    missing_recordings: list[str] = []
    requiring_validation: list[str] = []
    missing_score_rows: list[str] = []
    claimed_audio: set[Path] = set()

    for index, row in labels.iterrows():
        voice_id = normalize_identifier(row[voice_id_col])
        declared_name = (
            str(row[filename_col]).strip()
            if filename_col is not None and pd.notna(row[filename_col])
            else ""
        )
        candidates = (
            audio_by_name.get(Path(declared_name.replace("\\", "/")).name.casefold(), [])
            if declared_name
            else []
        )
        match_method = "filename"
        if len(candidates) != 1:
            candidates = canonical_audio_candidates(
                row[voice_id_col], audio_by_number
            )
            match_method = "voice_id"

        record_label = voice_id or f"row {index + 2}"
        if len(candidates) == 1:
            chosen = candidates[0]
            claimed_audio.add(chosen)
            detail = match_method
            if declared_name and chosen.name.casefold() != Path(
                declared_name.replace("\\", "/")
            ).name.casefold():
                detail += "; declared filename does not exist locally"
            matched_rows.append((record_label, chosen.name, detail))
        elif len(candidates) > 1:
            missing_recordings.append(
                f"{record_label}: ambiguous audio candidates "
                f"({format_items([path.name for path in candidates], limit=8)})"
            )
        else:
            missing_recordings.append(
                f"{record_label}: no local recording matched"
                + (f" ({declared_name})" if declared_name else "")
            )

        if not bool(verified_mask.loc[index]):
            requiring_validation.append(record_label)

        if score_columns:
            row_scores = pd.to_numeric(
                row[score_columns], errors="coerce"
            )
            if not row_scores.between(1, 5).all():
                missing_score_rows.append(record_label)

    label_numbers = {
        identifier_number(value) for value in labels[voice_id_col] if identifier_number(value)
    }
    audio_without_label = [
        path.name
        for path in audio_files
        if not identifier_number(path.name)
        or identifier_number(path.name) not in label_numbers
    ]

    hash_groups: dict[str, list[str]] = defaultdict(list)
    for path in audio_files:
        if path.stat().st_size == 0:
            hash_groups["<empty file>"].append(path.name)
        else:
            hash_groups[sha256_file(path)].append(path.name)
    duplicate_content_groups = [
        names for names in hash_groups.values() if len(names) > 1
    ]

    print("\nMatching report:")
    print(f"  Matched label rows: {len(matched_rows)}/{len(labels)}")
    for voice_id, filename, method in matched_rows:
        print(f"    {voice_id} -> {filename} [{method}]")
    print(f"  Label rows missing/ambiguous audio: {len(missing_recordings)}")
    for item in missing_recordings:
        print(f"    - {item}")
    print(f"  Audio files without a matching voice_id label: {len(audio_without_label)}")
    for item in audio_without_label:
        print(f"    - {item}")

    print("\nDuplicate/review report:")
    print(f"  Duplicate voice identifiers: {format_items(duplicate_voice_ids)}")
    print(f"  Duplicate student identifiers: {format_items(duplicate_student_ids)}")
    print(
        "  Repeated workbook filenames: "
        + (format_items(repeated_label_filenames) if repeated_label_filenames else "None")
    )
    print(f"  Byte-identical/empty audio groups: {len(duplicate_content_groups)}")
    for group in duplicate_content_groups:
        print(f"    - {format_items(group, limit=12)}")
    print(
        "  Note: duplicate audio is reported for manual review; no files are "
        "removed, merged, or assumed to be unique."
    )

    print("\nLabel validation report:")
    print(f"  Rows not fully teacher/identity validated: {len(requiring_validation)}")
    print(f"    {format_items(requiring_validation)}")
    print(f"  Rows with missing/invalid 1-5 target scores: {len(missing_score_rows)}")
    print(f"    {format_items(missing_score_rows)}")

    print("\nNext-step locations:")
    print(f"  Original recordings: {voice_root / 'raw'}")
    print(f"  Teacher labels: {voice_root / 'labels'}")
    print(f"  This workbook was discovered in its existing location: {workbook}")
    print(f"  Audio metadata/features/splits will be written under: {voice_root}")
    print("\nInspection complete. No source data was modified.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Read-only inspection of voice recordings and teacher labels."
    )
    parser.add_argument(
        "--audio-dir",
        type=Path,
        default=VOICE_ROOT,
        help=f"Voice data root (default: {VOICE_ROOT})",
    )
    parser.add_argument(
        "--labels",
        type=Path,
        default=None,
        help="Optional explicit .xlsx workbook path; auto-discovers VC01_Labels otherwise.",
    )
    parser.add_argument(
        "--sheet",
        default=EXPECTED_LABEL_SHEET,
        help=f"Teacher-label worksheet (default: {EXPECTED_LABEL_SHEET})",
    )
    args = parser.parse_args()
    try:
        return inspect_dataset(args.audio_dir, args.labels, args.sheet)
    except ImportError as exc:
        print(
            "A required checker dependency is missing. Install pandas and openpyxl "
            "in the selected environment before rerunning.",
            file=sys.stderr,
        )
        print(f"Details: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
