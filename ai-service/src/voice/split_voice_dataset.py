"""Create deterministic student-aware train/validation/test splits."""

from __future__ import annotations

import argparse
from pathlib import Path

import pandas as pd
from sklearn.model_selection import GroupShuffleSplit

from voice_common import SPLITS_ROOT, VOICE_ROOT, target_columns


FEATURES_PATH = VOICE_ROOT / "voice_features.csv"
SPLIT_PATH = SPLITS_ROOT / "voice_split.csv"
METADATA_COLUMNS = {
    "voice_id", "student_id", "activity_id", "declared_filename",
    "source_filename", "processed_path", "audio_sha256", "match_method",
    "declared_filename_matches", "teacher_verified", "creativity_class",
    "split",
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--features", type=Path, default=FEATURES_PATH)
    parser.add_argument("--output", type=Path, default=SPLIT_PATH)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    frame = pd.read_csv(args.features)
    required = {"student_id", "audio_sha256", "teacher_verified", *target_columns(frame)}
    missing = sorted(required - set(frame.columns))
    if missing:
        raise ValueError(f"Feature table is missing required columns: {missing}")

    verified = frame["teacher_verified"].astype(str).str.casefold().isin(
        {"true", "1", "yes"}
    )
    eligible = frame.loc[verified].copy()
    eligible = eligible[
        eligible["student_id"].fillna("").astype(str).str.strip().ne("")
        & eligible["audio_sha256"].fillna("").astype(str).str.strip().ne("")
    ].copy()
    targets = target_columns(eligible)
    for target in targets:
        eligible[target] = pd.to_numeric(eligible[target], errors="coerce")
    eligible = eligible.dropna(subset=targets)
    eligible = eligible[eligible[targets].apply(lambda col: col.between(1, 5)).all(axis=1)]

    # Identical waveforms carry identical acoustic inputs even when their
    # teacher scores differ. Exclude the entire hash group, never average labels.
    duplicate_hashes = set(
        eligible.loc[
            eligible["audio_sha256"].duplicated(keep=False), "audio_sha256"
        ].astype(str)
    )
    duplicate_rows = int(eligible["audio_sha256"].isin(duplicate_hashes).sum())
    eligible = eligible[~eligible["audio_sha256"].isin(duplicate_hashes)].copy()
    if eligible.empty:
        raise ValueError("No validated, unique audio samples remain after duplicate exclusion.")
    if eligible["student_id"].duplicated().any():
        raise ValueError(
            "Multiple retained recordings share a student ID. Group the recordings "
            "before using this exploratory one-record split."
        )

    unique_students = eligible["student_id"].nunique()
    if unique_students < 10:
        raise ValueError(
            f"Only {unique_students} independent students remain; a held-out "
            "70/15/15 split is not meaningful. Add independently verified data."
        )

    indices = eligible.index.to_numpy()
    groups = eligible["student_id"].astype(str).to_numpy()
    first_split = GroupShuffleSplit(
        n_splits=1, test_size=0.15, random_state=args.seed
    )
    train_val_positions, test_positions = next(
        first_split.split(indices, groups=groups)
    )
    train_val_indices = indices[train_val_positions]
    test_indices = indices[test_positions]
    train_val_groups = eligible.loc[train_val_indices, "student_id"].astype(str).to_numpy()
    validation_fraction = 0.15 / 0.85
    second_split = GroupShuffleSplit(
        n_splits=1, test_size=validation_fraction, random_state=args.seed + 1
    )
    train_positions, val_positions = next(
        second_split.split(train_val_indices, groups=train_val_groups)
    )
    train_indices = train_val_indices[train_positions]
    val_indices = train_val_indices[val_positions]

    split_by_index = {
        **{index: "train" for index in train_indices},
        **{index: "validation" for index in val_indices},
        **{index: "test" for index in test_indices},
    }
    result = frame.copy()
    result["split"] = result.index.map(split_by_index).fillna("excluded")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    result.to_csv(args.output, index=False, encoding="utf-8")

    student_sets = {
        split: set(
            result.loc[result["split"].eq(split), "student_id"]
            .dropna().astype(str)
        )
        for split in ("train", "validation", "test")
    }
    assert not student_sets["train"] & student_sets["validation"]
    assert not student_sets["train"] & student_sets["test"]
    assert not student_sets["validation"] & student_sets["test"]

    print(f"Validated rows read: {int(verified.sum())}")
    print(f"Excluded rows with missing/invalid targets or IDs: {len(frame) - int(verified.sum())}")
    print(f"Rows excluded due to duplicated audio content: {duplicate_rows}")
    print(f"Independent students retained: {unique_students}")
    for split in ("train", "validation", "test"):
        count = int(result["split"].eq(split).sum())
        print(f"{split}: {count} recordings, {len(student_sets[split])} students")
    print("Student leakage check: PASS")
    print(f"Split CSV: {args.output}")
    print(
        "Caution: this is a small, exploratory dataset; test metrics have high "
        "uncertainty and are not evidence of classroom readiness."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
