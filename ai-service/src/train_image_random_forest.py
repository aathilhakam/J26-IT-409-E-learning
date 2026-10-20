"""Train a Random Forest on images paired with teacher creativity scores.

Run from ai-service:
    python src/train_image_random_forest.py
"""

from __future__ import annotations

import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from PIL import Image
from sklearn.ensemble import RandomForestRegressor
from sklearn.metrics import mean_absolute_error, r2_score

from src.image_features import FEATURE_VERSION, extract_image_features


ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = ROOT / "data"
SPLIT_DIR = DATA_DIR / "splits"
MODEL_DIR = ROOT / "models"
RESULT_DIR = ROOT / "results"
MODEL_PATH = MODEL_DIR / "image_random_forest.joblib"
METRICS_PATH = RESULT_DIR / "image_random_forest_metrics.json"

TARGETS = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization",
]


def load_split(name: str) -> pd.DataFrame:
    path = SPLIT_DIR / f"{name}.csv"
    if not path.is_file():
        raise FileNotFoundError(
            f"Missing {name} split: {path}. "
            "Prepare the image training dataset and splits first."
        )
    frame = pd.read_csv(path)
    required = {"image_path", "student_id", *TARGETS}
    missing = sorted(required.difference(frame.columns))
    if missing:
        raise ValueError(f"{path} is missing required columns: {missing}")
    if frame.empty:
        raise ValueError(f"{path} contains no labeled image rows.")
    if frame[TARGETS].isna().any().any():
        raise ValueError(f"{path} contains missing teacher scores.")
    if not frame[TARGETS].apply(lambda column: column.between(1, 5).all()).all():
        raise ValueError(f"{path} contains teacher scores outside 1–5.")
    return frame


def build_features(frame: pd.DataFrame) -> np.ndarray:
    rows = []
    for image_path in frame["image_path"]:
        path = Path(str(image_path))
        if not path.is_file():
            raise FileNotFoundError(f"Training image not found: {path}")
        with Image.open(path) as image:
            rows.append(extract_image_features(image))
    return np.vstack(rows)


def main() -> None:
    train = load_split("train")
    validation = load_split("val")
    test = load_split("test")

    train_students = set(train["student_id"].astype(str))
    validation_students = set(validation["student_id"].astype(str))
    test_students = set(test["student_id"].astype(str))
    if train_students & validation_students or train_students & test_students or validation_students & test_students:
        raise ValueError("Student leakage detected across the provided image splits.")

    training = pd.concat([train, validation], ignore_index=True)
    print(
        f"Training on {len(training)} teacher-scored images from "
        f"{training['student_id'].nunique()} students; "
        f"holding out {len(test)} images from {len(test_students)} students."
    )

    x_train = build_features(training)
    y_train = training[TARGETS].to_numpy(dtype=np.float32)
    x_test = build_features(test)
    y_test = test[TARGETS].to_numpy(dtype=np.float32)

    model = RandomForestRegressor(
        n_estimators=500,
        max_features=0.7,
        min_samples_leaf=2,
        random_state=42,
        n_jobs=-1,
    )
    model.fit(x_train, y_train)
    predictions = np.clip(model.predict(x_test), 1.0, 5.0)

    overall_mae = float(mean_absolute_error(y_test, predictions))
    baseline = np.tile(y_train.mean(axis=0), (len(y_test), 1))
    baseline_mae = float(mean_absolute_error(y_test, baseline))
    metrics = {
        "model": "RandomForestRegressor",
        "feature_version": FEATURE_VERSION,
        "train_images": int(len(training)),
        "train_students": int(len(training["student_id"].unique())),
        "test_images": int(len(test)),
        "test_students": int(len(test_students)),
        "test_mae": overall_mae,
        "train_mean_baseline_mae": baseline_mae,
        "target_metrics": {
            target: {
                "mae": float(mean_absolute_error(y_test[:, index], predictions[:, index])),
                "r2": float(r2_score(y_test[:, index], predictions[:, index])),
            }
            for index, target in enumerate(TARGETS)
        },
    }

    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    RESULT_DIR.mkdir(parents=True, exist_ok=True)
    joblib.dump(
        {
            "model": model,
            "targets": TARGETS,
            "feature_version": FEATURE_VERSION,
            "metrics": metrics,
        },
        MODEL_PATH,
    )
    METRICS_PATH.write_text(json.dumps(metrics, indent=2), encoding="utf-8")
    print(json.dumps(metrics, indent=2))
    print(f"Saved image model: {MODEL_PATH}")
    print(f"Saved held-out evaluation: {METRICS_PATH}")


if __name__ == "__main__":
    main()
