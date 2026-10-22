"""Train non-pretrained XGBoost regressors on verified, unique voice features."""

from __future__ import annotations

import argparse
import json
import warnings
from pathlib import Path
from typing import Any

import joblib
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sklearn
import xgboost
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.model_selection import GroupKFold, RandomizedSearchCV
from xgboost import XGBRegressor

from voice_common import MODEL_ROOT, RESULT_ROOT, SPLITS_ROOT, VOICE_ROOT, target_columns, target_category


SPLIT_PATH = SPLITS_ROOT / "voice_split.csv"
MODEL_PATH = MODEL_ROOT / "voice_xgboost_regressors.joblib"
PARAMETERS_PATH = RESULT_ROOT / "hyperparameters.json"
METRICS_PATH = RESULT_ROOT / "metrics.json"
CV_RESULTS_PATH = RESULT_ROOT / "cross_validation_results.csv"
PREDICTIONS_PATH = RESULT_ROOT / "predictions.csv"
IMPORTANCE_PATH = RESULT_ROOT / "feature_importance.png"
NON_FEATURE_COLUMNS = {
    "voice_id", "student_id", "activity_id", "declared_filename",
    "source_filename", "processed_path", "audio_sha256", "match_method",
    "declared_filename_matches", "teacher_verified", "creativity_class", "split",
}

PARAMETER_DISTRIBUTIONS = {
    "n_estimators": [50, 100, 200, 300],
    "max_depth": [2, 3, 4],
    "learning_rate": [0.01, 0.03, 0.05, 0.1],
    "subsample": [0.7, 0.85, 1.0],
    "colsample_bytree": [0.7, 0.85, 1.0],
    "min_child_weight": [1, 2, 3],
    "reg_alpha": [0, 0.1, 1.0],
    "reg_lambda": [1, 3, 5],
}


def regression_metrics(actual: np.ndarray, predicted: np.ndarray) -> dict[str, float | None]:
    if len(actual) == 0:
        return {"mae": None, "rmse": None, "r2": None}
    r2 = float(r2_score(actual, predicted)) if len(actual) >= 2 else None
    return {
        "mae": float(mean_absolute_error(actual, predicted)),
        "rmse": float(np.sqrt(mean_squared_error(actual, predicted))),
        "r2": r2,
    }


def round_parameters(parameters: dict[str, Any]) -> dict[str, Any]:
    return {key: value.item() if isinstance(value, np.generic) else value for key, value in parameters.items()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", type=Path, default=SPLIT_PATH)
    parser.add_argument("--iterations", type=int, default=12)
    parser.add_argument("--cv-folds", type=int, default=3)
    parser.add_argument("--seed", type=int, default=42)
    args = parser.parse_args()

    frame = pd.read_csv(args.split)
    targets = target_columns(frame)
    if not targets:
        raise ValueError("No teacher score columns ending in _1_5 were found.")
    feature_columns = [
        column for column in frame.select_dtypes(include=[np.number]).columns
        if column not in NON_FEATURE_COLUMNS and column not in targets
    ]
    if not feature_columns:
        raise ValueError("No numeric handcrafted acoustic features were found.")

    train = frame.loc[frame["split"].eq("train")].copy()
    validation = frame.loc[frame["split"].eq("validation")].copy()
    test = frame.loc[frame["split"].eq("test")].copy()
    if train.empty or validation.empty or test.empty:
        raise ValueError("Train, validation, and test splits must all contain samples.")
    if train["student_id"].astype(str).duplicated().any():
        raise ValueError("Training split contains multiple recordings for one student.")
    if len(train) < 6:
        raise ValueError(
            f"Only {len(train)} training samples remain; tuning would be unreliable."
        )

    fold_count = min(args.cv_folds, train["student_id"].nunique())
    if fold_count < 2:
        raise ValueError("At least two independent training students are needed for CV.")
    cv = GroupKFold(n_splits=fold_count)
    x_train = train[feature_columns]
    groups = train["student_id"].astype(str).to_numpy()
    cv_records: list[pd.DataFrame] = []
    prediction_records: list[dict[str, object]] = []
    factor_metrics: dict[str, Any] = {}
    models: dict[str, XGBRegressor] = {}
    importance_values: list[np.ndarray] = []
    best_parameters: dict[str, dict[str, Any]] = {}

    for target in targets:
        y_train = pd.to_numeric(train[target], errors="coerce")
        valid_train = y_train.between(1, 5)
        if not valid_train.all():
            raise ValueError(f"Invalid or missing training scores for {target}.")
        if y_train.nunique() < 2:
            print(f"WARNING: {target} has one score value in training; regression is not informative.")

        estimator = XGBRegressor(
            objective="reg:squarederror",
            tree_method="hist",
            random_state=args.seed,
            n_jobs=1,
            verbosity=0,
        )
        search = RandomizedSearchCV(
            estimator=estimator,
            param_distributions=PARAMETER_DISTRIBUTIONS,
            n_iter=args.iterations,
            scoring="neg_mean_absolute_error",
            cv=cv,
            random_state=args.seed,
            refit=True,
            n_jobs=1,
            error_score="raise",
            return_train_score=True,
        )
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", category=UserWarning)
            search.fit(x_train, y_train, groups=groups)
        model = search.best_estimator_
        models[target] = model
        best_parameters[target] = round_parameters(search.best_params_)
        cv_frame = pd.DataFrame(search.cv_results_)
        cv_frame.insert(0, "target", target)
        cv_frame["params_json"] = cv_frame["params"].map(
            lambda value: json.dumps(round_parameters(value), sort_keys=True)
        )
        cv_records.append(cv_frame.drop(columns=["params"], errors="ignore"))
        importance_values.append(np.asarray(model.feature_importances_, dtype=float))

        target_result: dict[str, Any] = {
            "category": target_category(target),
            "valid_train_rows": int(len(train)),
            "validation_rows": int(len(validation)),
            "test_rows": int(len(test)),
            "cv_best_mae": float(-search.best_score_),
            "best_parameters": best_parameters[target],
        }
        for split_name, split_frame in (
            ("train", train),
            ("validation", validation),
            ("test", test),
        ):
            actual = pd.to_numeric(split_frame[target], errors="coerce").to_numpy(float)
            predicted = np.clip(model.predict(split_frame[feature_columns]), 1.0, 5.0)
            scores = regression_metrics(actual, predicted)
            target_result[split_name] = scores
            if split_name == "test":
                baseline_value = float(y_train.mean())
                baseline = np.full(len(actual), baseline_value)
                baseline_scores = regression_metrics(actual, baseline)
                target_result["test_mean_baseline"] = baseline_scores
                if scores["mae"] is not None and baseline_scores["mae"] is not None:
                    target_result["worse_than_mean_baseline"] = (
                        scores["mae"] > baseline_scores["mae"]
                    )
                    if target_result["worse_than_mean_baseline"]:
                        print(
                            f"WARNING: {target} test MAE is worse than the "
                            "training-mean baseline."
                        )
            for record_index, (_, record) in enumerate(split_frame.iterrows()):
                prediction_records.append({
                    "target": target,
                    "split": split_name,
                    "voice_id": record.get("voice_id", ""),
                    "student_id": record.get("student_id", ""),
                    "actual": float(actual[record_index]),
                    "predicted": float(predicted[record_index]),
                    "audio_sha256": record.get("audio_sha256", ""),
                })

        rounds = int(model.get_booster().num_boosted_rounds())
        dumped_trees = len(model.get_booster().get_dump(dump_format="json"))
        target_result["actual_boosting_rounds"] = rounds
        target_result["actual_decision_trees"] = dumped_trees
        factor_metrics[target] = target_result
        print(
            f"{target}: test MAE={target_result['test']['mae']:.3f}, "
            f"RMSE={target_result['test']['rmse']:.3f}, "
            f"R2={target_result['test']['r2']}"
        )

    MODEL_ROOT.mkdir(parents=True, exist_ok=True)
    RESULT_ROOT.mkdir(parents=True, exist_ok=True)
    cv_output = pd.concat(cv_records, ignore_index=True)
    cv_output.to_csv(CV_RESULTS_PATH, index=False, encoding="utf-8")
    pd.DataFrame(prediction_records).to_csv(
        PREDICTIONS_PATH, index=False, encoding="utf-8"
    )
    joblib.dump({
        "models": models,
        "feature_columns": feature_columns,
        "targets": targets,
        "feature_method": (
            "Handcrafted librosa acoustic statistics (MFCC/deltas, RMS, ZCR, "
            "spectral descriptors, chroma, YIN pitch, silence/pause proxy); "
            "no pretrained model or embedding."
        ),
        "sample_rate": 16_000,
        "target_range": [1, 5],
    }, MODEL_PATH)
    with PARAMETERS_PATH.open("w", encoding="utf-8") as stream:
        json.dump({
            "model": "XGBRegressor",
            "pretrained": False,
            "tree_method": "hist",
            "random_state": args.seed,
            "randomized_search_iterations_per_target": args.iterations,
            "cv": f"GroupKFold(n_splits={fold_count}), grouped by student_id",
            "selection_metric": "negative mean absolute error",
            "search_space": PARAMETER_DISTRIBUTIONS,
            "best_parameters": best_parameters,
        }, stream, indent=2)

    average_importance = np.mean(np.vstack(importance_values), axis=0)
    top_indices = np.argsort(average_importance)[-25:]
    fig, axis = plt.subplots(figsize=(10, max(5, len(top_indices) * 0.28)))
    axis.barh(
        [feature_columns[index] for index in top_indices],
        average_importance[top_indices],
    )
    axis.set_title("Mean XGBoost feature importance across teacher score factors")
    axis.set_xlabel("Gain-based feature importance")
    fig.tight_layout()
    fig.savefig(IMPORTANCE_PATH, dpi=160)
    plt.close(fig)

    metrics = {
        "model": "XGBoost regressors with handcrafted acoustic features",
        "pretrained": False,
        "language_context": "Recordings are labeled Sinhala; acoustic features are language-agnostic.",
        "exploratory_id_match_assumption": "V### teacher voice_id corresponds to VC### audio basename.",
        "duplicate_policy": "Excluded every repeated SHA-256 audio group; no scores were averaged.",
        "audio_feature_method": "Handcrafted; no WavLM, Whisper, pretrained CNN, or pretrained embeddings.",
        "feature_count": len(feature_columns),
        "target_count": len(targets),
        "train_samples": int(len(train)),
        "validation_samples": int(len(validation)),
        "test_samples": int(len(test)),
        "independent_train_students": int(train["student_id"].nunique()),
        "independent_validation_students": int(validation["student_id"].nunique()),
        "independent_test_students": int(test["student_id"].nunique()),
        "small_dataset_warning": (
            "The dataset is exploratory and the test set is small; metrics are "
            "high variance and must not be interpreted as classroom readiness."
        ),
        "classifier": {
            "trained": False,
            "reason": "The workbook uses Weak/Moderate/High, not validated Developing/Moderate/High labels.",
        },
        "software": {"xgboost": xgboost.__version__, "scikit_learn": sklearn.__version__},
        "targets": factor_metrics,
        "artifacts": {
            "model_bundle": str(MODEL_PATH),
            "hyperparameters": str(PARAMETERS_PATH),
            "cross_validation_results": str(CV_RESULTS_PATH),
            "predictions": str(PREDICTIONS_PATH),
            "feature_importance_chart": str(IMPORTANCE_PATH),
        },
    }
    with METRICS_PATH.open("w", encoding="utf-8") as stream:
        json.dump(metrics, stream, indent=2, allow_nan=False)
    print(f"\nModel bundle: {MODEL_PATH}")
    print(f"Metrics: {METRICS_PATH}")
    print(f"CV results: {CV_RESULTS_PATH}")
    print(f"Prediction results: {PREDICTIONS_PATH}")
    print(f"Feature importance: {IMPORTANCE_PATH}")
    print("Model training completed; results are exploratory only.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
