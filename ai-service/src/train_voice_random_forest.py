"""
SmartPlay Component 3 - Voice Random Forest Regression (baseline).

Run from ai-service:
    python src/train_voice_random_forest.py --mode prepare
    # REVIEW voice_manifest.csv manually; add correct student IDs and confirm matches
    python src/train_voice_random_forest.py --mode check
    python src/train_voice_random_forest.py --mode train

Uses genuine, teacher-validated ratings for six voice-delivery dimensions.
Does NOT infer student identity or invent teacher scores. Audio filenames
are suggested matches, not evidence that a match is correct.
"""

import argparse
import hashlib
import json
import re
from pathlib import Path

import joblib
import librosa
import numpy as np
import pandas as pd

from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import GroupShuffleSplit


ROOT = Path(__file__).resolve().parents[1]
EXCEL = ROOT / "data" / "labels" / "VC01.xlsx"
MANIFEST = ROOT / "data" / "labels" / "voice_manifest.csv"
AUDIO_ROOT = ROOT / "data" / "audio"  # supports .ogg or .wav in nested folders

MODEL_DIR = ROOT / "models"
RESULT_DIR = ROOT / "results"
SPLIT_DIR = ROOT / "data" / "splits"

SAMPLE_RATE = 16000
MAX_AUDIO_SECONDS = 60.0
SEED = 42

TARGETS = [
    "expressiveness_1_5",
    "fluency_1_5",
    "rhythm_1_5",
    "vocal_variety_1_5",
    "engagement_1_5",
    "naturalness_1_5",
]

SUPPORTED_AUDIO = {".wav", ".ogg", ".mp3", ".m4a", ".mp4", ".flac"}
FEATURE_NAMES = (
    [f"mfcc_{i:02d}_mean" for i in range(1, 14)]
    + [f"mfcc_{i:02d}_std" for i in range(1, 14)]
    + [
        "zcr_mean", "zcr_std", "rms_mean", "rms_std",
        "centroid_mean", "centroid_std", "rolloff_mean",
        "rolloff_std", "analyzed_duration_seconds",
    ]
)


def read_labels():
    if not EXCEL.is_file():
        raise FileNotFoundError(f"Missing labels workbook: {EXCEL}")

    try:
        df = pd.read_excel(EXCEL, sheet_name="VC01_Labels")
    except ValueError as exc:
        raise ValueError(
            "The Excel file must contain the sheet 'VC01_Labels'."
        ) from exc

    expected = ["voice_id", "label_status", "teacher_verification", *TARGETS]
    missing = [col for col in expected if col not in df.columns]
    if missing:
        raise ValueError(f"Missing columns in VC01.xlsx: {missing}")

    df["voice_id"] = df["voice_id"].fillna("").astype(str).str.strip()
    if (df["voice_id"] == "").any():
        raise ValueError("VC01.xlsx contains blank voice_id cells.")
    if df["voice_id"].duplicated().any():
        raise ValueError("VC01.xlsx contains duplicate voice_id values.")
    return df


def discover_audio_files():
    if not AUDIO_ROOT.is_dir():
        raise FileNotFoundError(
            f"No audio folder found: {AUDIO_ROOT}\n"
            "Create data/audio and add WAV or OGG recordings first."
        )
    return sorted(
        (p for p in AUDIO_ROOT.rglob("*")
         if p.is_file() and p.suffix.lower() in SUPPORTED_AUDIO),
        key=lambda p: str(p).lower()
    )


def make_manifest():
    # Never silently destroy manually confirmed records.
    if MANIFEST.exists():
        print(f"Manifest already exists; preserving it:\n{MANIFEST}")
        current = pd.read_csv(MANIFEST, dtype=str).fillna("")
        print("Rows:", len(current))
        if "match_confirmed" in current.columns:
            print("Marked YES:",
                  current["match_confirmed"].str.strip().str.upper().eq("YES").sum())
        return

    labels = read_labels()
    audio_files = discover_audio_files()
    by_name = {}
    for path in audio_files:
        by_name.setdefault(path.name.lower(), []).append(path)

    rows = []
    for _, record in labels.iterrows():
        voice_id = record["voice_id"]
        desired = []

        # First try the filename supplied by the original labels workbook.
        if "filename" in labels.columns and pd.notna(record["filename"]):
            label_filename = Path(str(record["filename"]).replace("\\", "/")).name
            if label_filename:
                desired.append(label_filename)

        match = re.fullmatch(r"V(\d+)", voice_id, flags=re.IGNORECASE)
        if match:
            num = int(match.group(1))
            bases = [f"VC{num:03d}", f"V{num:03d}_VC01", f"V{num:03d}"]
            for base in bases:
                for ext in [".ogg", ".wav", ".mp3", ".m4a", ".flac", ".mp4"]:
                    desired.append(base + ext)

        found = None
        status = "not found"
        for name in dict.fromkeys(desired):  # stable unique candidates
            matches = by_name.get(name.lower(), [])
            if len(matches) == 1:
                found = matches[0]
                status = "suggested"
                break
            if len(matches) > 1:
                status = "ambiguous filename (multiple folders)"
                break

        # Keep existing column name "wav_filename" for compatibility;
        # it can contain .ogg as well as .wav.
        relative_name = found.relative_to(AUDIO_ROOT).as_posix() if found else ""
        rows.append({
            "voice_id": voice_id,
            "student_id": "",
            "wav_filename": relative_name,
            "match_confirmed": "",
        })
        if status != "suggested":
            print(f"REVIEW {voice_id}: {status}")

    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    pd.DataFrame(rows).to_csv(MANIFEST, index=False, encoding="utf-8-sig")
    print(f"\nManifest created: {MANIFEST}")
    print(f"Excel label rows: {len(rows)}")
    print(f"Audio files found under data/audio: {len(audio_files)}")
    print(f"Suggested filenames: {sum(bool(row['wav_filename']) for row in rows)}")
    print("\nNEXT: Verify each suggestion against original recording records.")
    print("Enter the genuine pseudonymous student_id (same child = same ID).")
    print("Set match_confirmed=YES only for verified recording/label matches.")
    print("Keep child recordings and identification information private.")


def resolve_audio(filename):
    # Reject absolute paths and ../ to prevent escaping data/audio.
    relative = Path(filename.replace("\\", "/"))
    if relative.is_absolute():
        raise ValueError(f"Absolute audio path not allowed: {filename}")
    base = AUDIO_ROOT.resolve()
    path = (base / relative).resolve()
    try:
        path.relative_to(base)
    except ValueError as exc:
        raise ValueError(f"Audio path outside data/audio: {filename}") from exc

    if not path.is_file():
        raise FileNotFoundError(f"Audio missing: {path}")
    if path.suffix.lower() not in SUPPORTED_AUDIO:
        raise ValueError(f"Unsupported audio extension: {path.suffix}")
    return path


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verified_dataset():
    if not MANIFEST.exists():
        raise FileNotFoundError(
            f"Manifest missing: {MANIFEST}\n"
            "First run: python src/train_voice_random_forest.py --mode prepare"
        )

    labels = read_labels()
    manifest = pd.read_csv(MANIFEST, dtype=str).fillna("")
    columns = ["voice_id", "student_id", "wav_filename", "match_confirmed"]
    missing = [name for name in columns if name not in manifest.columns]
    if missing:
        raise ValueError(f"Missing manifest columns: {missing}")

    for name in columns:
        manifest[name] = manifest[name].astype(str).str.strip()

    if manifest["voice_id"].duplicated().any():
        raise ValueError("Duplicate voice_id values in voice_manifest.csv.")

    confirmed = manifest[
        manifest["match_confirmed"].str.upper().eq("YES")
    ].copy()

    if confirmed.empty:
        raise ValueError(
            "0 audio-to-label matches are confirmed. Open voice_manifest.csv, "
            "verify each match, enter real student IDs, and mark YES only "
            "when confirmed."
        )

    if confirmed["student_id"].eq("").any():
        bad = confirmed.loc[confirmed["student_id"].eq(""), "voice_id"].tolist()
        raise ValueError(f"Confirmed recordings missing student_id: {bad}")
    if confirmed["wav_filename"].eq("").any():
        bad = confirmed.loc[confirmed["wav_filename"].eq(""), "voice_id"].tolist()
        raise ValueError(f"Confirmed recordings missing audio filename: {bad}")

    # Left merge ensures unmatched manifest IDs cannot vanish silently.
    df = confirmed.merge(labels, on="voice_id", how="left",
                         validate="one_to_one", indicator=True)
    unknown = df.loc[df["_merge"] != "both", "voice_id"].tolist()
    if unknown:
        raise ValueError(f"Manifest voice IDs missing from Excel: {unknown}")
    df = df.drop(columns=["_merge"])

    label_status = df["label_status"].fillna("").astype(str).str.strip().str.lower()
    teacher_status = (
        df["teacher_verification"].fillna("").astype(str).str.strip()
        .str.lower().str.replace("_", " ", regex=False)
    )
    df = df[
        label_status.eq("validated") & teacher_status.eq("teacher verified")
    ].copy()
    if df.empty:
        raise ValueError(
            "No recordings with both label_status='Validated' and "
            "teacher_verification='Teacher verified'. Check the actual "
            "teacher review records. Do not mark unreviewed labels validated."
        )

    for col in TARGETS:
        df[col] = pd.to_numeric(df[col], errors="coerce")

    before = len(df)
    df = df.dropna(subset=TARGETS).copy()
    if df.empty:
        raise ValueError("All verified recordings have missing teacher score(s).")
    if len(df) < before:
        print(f"WARNING: Excluded {before - len(df)} records with missing scores.")

    valid_scores = df[TARGETS].apply(lambda s: s.between(1, 5)).all(axis=1)
    if not valid_scores.all():
        bad = df.loc[~valid_scores, "voice_id"].tolist()
        raise ValueError(f"Teacher scores outside 1-5 for: {bad}")

    paths, hashes = [], []
    for name in df["wav_filename"]:
        path = resolve_audio(name)
        paths.append(path)
        hashes.append(sha256_file(path))
    df["audio_hash"] = hashes

    duplicated = df[df["audio_hash"].duplicated(keep=False)]
    if not duplicated.empty:
        raise ValueError(
            "Byte-identical audio duplicates detected (potential leakage). "
            "Review and deduplicate these rows:\n"
            + duplicated[["voice_id", "wav_filename", "student_id"]]
              .to_string(index=False)
        )

    # Important: identical content with different encodings won't necessarily
    # have equal byte hashes. Verify those duplicates manually too.
    student_count = df["student_id"].nunique()
    if student_count < 5:
        raise ValueError(
            f"Found only {student_count} distinct validated student IDs. "
            "At least 5 are required by this preliminary grouped-split "
            "pipeline; collect/verify additional independent participants."
        )

    return df.reset_index(drop=True), paths


def extract_features(path):
    try:
        y, sr = librosa.load(
            str(path), sr=SAMPLE_RATE, mono=True, duration=MAX_AUDIO_SECONDS
        )
    except Exception as exc:
        raise RuntimeError(
            f"Failed to decode {path}. Verify the codec and convert to "
            "PCM WAV with an audio converter if needed."
        ) from exc

    if len(y) < SAMPLE_RATE:
        raise ValueError(
            f"Audio too short (<1 second): {path}; review/replace recording."
        )

    y = np.asarray(y, dtype=np.float32)
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=13)
    zcr = librosa.feature.zero_crossing_rate(y)
    rms = librosa.feature.rms(y=y)
    centroid = librosa.feature.spectral_centroid(y=y, sr=sr)
    rolloff = librosa.feature.spectral_rolloff(y=y, sr=sr)

    feats = [*np.mean(mfcc, axis=1), *np.std(mfcc, axis=1)]
    for values in [zcr, rms, centroid, rolloff]:
        feats.extend([float(np.mean(values)), float(np.std(values))])
    feats.append(float(len(y) / sr))

    arr = np.asarray(feats, dtype=np.float32)
    if len(arr) != len(FEATURE_NAMES) or not np.isfinite(arr).all():
        raise ValueError(f"Invalid extracted features for {path}")
    return arr


def check():
    df, paths = verified_dataset()
    print("\nDATA CHECK PASSED")
    print("Verified, eligible recordings:", len(df))
    print("Distinct students:", df["student_id"].nunique())
    print("Audio paths checked:", len(paths))
    print("No model has been trained yet.")


def train():
    df, paths = verified_dataset()
    print("Eligible recordings:", len(df))
    print("Distinct students:", df["student_id"].nunique())

    # Perform the split BY STUDENT, not by file, to prevent leakage.
    splitter = GroupShuffleSplit(n_splits=1, test_size=0.30,
                                 random_state=SEED)
    indices = np.arange(len(df))
    train_idx, test_idx = next(
        splitter.split(indices, groups=df["student_id"])
    )

    train_students = set(df.iloc[train_idx]["student_id"])
    test_students = set(df.iloc[test_idx]["student_id"])
    assert not train_students.intersection(test_students), "Student leakage"

    if len(train_idx) < 3 or len(test_idx) < 2:
        raise ValueError(
            "Not enough recordings in the grouped training/test split. "
            "Collect more verified recordings."
        )

    print("Extracting audio features (this can take several minutes)...")
    X = np.stack([extract_features(path) for path in paths])
    Y = df[TARGETS].to_numpy(dtype=np.float32)

    X_train, X_test = X[train_idx], X[test_idx]
    y_train, y_test = Y[train_idx], Y[test_idx]

    model = RandomForestRegressor(
        n_estimators=200,
        max_features=0.8,
        min_samples_leaf=2,
        random_state=SEED,
        n_jobs=-1,
    )
    model.fit(X_train, y_train)
    pred = np.clip(model.predict(X_test), 1.0, 5.0)

    mae = float(mean_absolute_error(y_test, pred))
    rmse = float(np.sqrt(mean_squared_error(y_test, pred)))
    r2 = float(r2_score(y_test, pred))

    per_target = {}
    for k, target in enumerate(TARGETS):
        per_target[target] = {
            "mae": float(mean_absolute_error(y_test[:, k], pred[:, k])),
            "rmse": float(np.sqrt(
                mean_squared_error(y_test[:, k], pred[:, k])
            )),
            "r2": float(r2_score(y_test[:, k], pred[:, k])),
        }

    for folder in (MODEL_DIR, RESULT_DIR, SPLIT_DIR):
        folder.mkdir(parents=True, exist_ok=True)

    model_file = MODEL_DIR / "voice_random_forest.joblib"
    joblib.dump({
        "model": model,
        "targets": TARGETS,
        "feature_names": FEATURE_NAMES,
        "sample_rate": SAMPLE_RATE,
        "max_audio_seconds": MAX_AUDIO_SECONDS,
    }, model_file)

    metrics = {
        "model": "Random Forest Regressor",
        "pretrained": False,
        "task": "teacher-rated voice-delivery score regression",
        "train_samples": int(len(train_idx)),
        "test_samples": int(len(test_idx)),
        "train_student_count": len(train_students),
        "test_student_count": len(test_students),
        "overall_mae": mae,
        "overall_rmse": rmse,
        "overall_r2": r2,
        "per_target": per_target,
        "warning": (
            "Small-sample exploratory results. Student-group holdout; "
            "teacher ratings assess vocal delivery, not full story creativity."
        ),
    }
    (RESULT_DIR / "voice_random_forest_metrics.json").write_text(
        json.dumps(metrics, indent=2), encoding="utf-8"
    )

    split_df = df[["voice_id", "student_id", "wav_filename"]].copy()
    split_df["split"] = ""
    split_df.loc[train_idx, "split"] = "train"
    split_df.loc[test_idx, "split"] = "test"
    split_df.to_csv(
        SPLIT_DIR / "voice_random_forest_split.csv",
        index=False, encoding="utf-8-sig"
    )

    predictions = pd.DataFrame({
        "voice_id": df.iloc[test_idx]["voice_id"].to_numpy(),
        "student_id": df.iloc[test_idx]["student_id"].to_numpy(),
    })
    for i, name in enumerate(TARGETS):
        predictions[f"{name}_actual"] = y_test[:, i]
        predictions[f"{name}_predicted"] = np.round(pred[:, i], 3)
    predictions.to_csv(
        RESULT_DIR / "voice_random_forest_test_predictions.csv",
        index=False, encoding="utf-8-sig"
    )

    print("\n========= RANDOM FOREST TEST RESULTS =========")
    print(f"Training records: {len(train_idx)}")
    print(f"Test records    : {len(test_idx)}")
    print(f"MAE             : {mae:.4f}")
    print(f"RMSE            : {rmse:.4f}")
    print(f"R2              : {r2:.4f}")
    print("==============================================")
    print("Saved model :", model_file)
    print("Saved metrics:", RESULT_DIR / "voice_random_forest_metrics.json")
    print("Saved split  :", SPLIT_DIR / "voice_random_forest_split.csv")
    print("NOTE: These are regression metrics, not classification accuracy.")


def main():
    parser = argparse.ArgumentParser(
        description="SmartPlay Component 3 - Random Forest voice baseline"
    )
    parser.add_argument(
        "--mode", choices=["prepare", "check", "train"], required=True
    )
    args = parser.parse_args()

    if args.mode == "prepare":
        make_manifest()
    elif args.mode == "check":
        check()
    elif args.mode == "train":
        train()


if __name__ == "__main__":
    main()
