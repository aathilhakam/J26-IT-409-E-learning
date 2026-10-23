"""Train an exploratory binary Random Forest from the repository's old labels.

This artifact is not suitable for production: labels are dominant historical
lesson outcomes, tied labels depend on row order, and the sample is tiny.
The live 28-feature model and inference service are never modified.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import joblib
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (accuracy_score, classification_report, confusion_matrix,
                             f1_score, precision_score, recall_score)
from sklearn.model_selection import LeaveOneOut, cross_val_predict
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

ROOT = Path(__file__).resolve().parents[1]
FEATURE_PATH = ROOT / "data" / "processed" / "student_features.csv"
HISTORY_PATH = ROOT / "data" / "processed" / "historical_clean.csv"
OUTPUT = ROOT / "models" / "candidates" / "binary-10-feature-prototype"
FEATURES = [
    "Average_Attempts", "Total_Activities", "Completion_Rate",
    "Participation", "Attention_Level", "Collaboration", "Learning_Difficulty",
    "Historical_Lessons", "Completed_Historical_Lessons", "Historical_Completion_Rate",
]
CLASSES = ["Needs Practice", "Successful"]


def main() -> int:
    features = pd.read_csv(FEATURE_PATH, dtype={"Student_ID": str})
    history = pd.read_csv(HISTORY_PATH, dtype={"Student_ID": str})
    missing = sorted(set(["Student_ID", *FEATURES]) - set(features.columns))
    if missing:
        raise SystemExit(f"Training stopped: feature table is missing {missing}")
    if history[["Student_ID", "Outcome"]].isna().any().any():
        raise SystemExit("Training stopped: historical target data contains missing student or outcome values.")
    if features.Student_ID.duplicated().any():
        raise SystemExit("Training stopped: feature table has duplicate student IDs.")

    # Match notebook 04 exactly: value_counts().idxmax() chooses the first most
    # frequent outcome encountered in row order when a student's counts tie.
    targets = (history.groupby("Student_ID", sort=True)["Outcome"]
               .agg(lambda values: values.value_counts().idxmax()).rename("Target_Outcome"))
    merged = features.merge(targets, left_on="Student_ID", right_index=True, how="inner", validate="one_to_one")
    all_target_counts = merged.Target_Outcome.value_counts().to_dict()
    eligible = merged[merged.Target_Outcome.isin(CLASSES)].copy()
    excluded = merged[~merged.Target_Outcome.isin(CLASSES)][["Student_ID", "Target_Outcome"]]
    tie_counts = history.groupby("Student_ID")["Outcome"].apply(lambda outcomes: outcomes.value_counts().eq(outcomes.value_counts().max()).sum())
    tied_ids = sorted(tie_counts[tie_counts > 1].index.astype(str).tolist())

    if eligible.Target_Outcome.nunique() != 2:
        raise SystemExit("Training stopped: existing history does not yield both requested binary classes.")
    X = eligible[FEATURES].copy()
    y = eligible.Target_Outcome.copy()
    if X.isna().any().any() or not all(X[column].map(lambda value: isinstance(value, str) and bool(value.strip()) if X[column].dtype == object else pd.notna(value)).all() for column in FEATURES):
        raise SystemExit("Training stopped: selected features contain missing values.")
    numeric = [name for name in FEATURES if pd.api.types.is_numeric_dtype(X[name])]
    categorical = [name for name in FEATURES if name not in numeric]
    preprocessor = ColumnTransformer([
        ("numeric", StandardScaler(), numeric),
        ("categorical", OneHotEncoder(handle_unknown="ignore", sparse_output=False), categorical),
    ])
    model = Pipeline([
        ("preprocessor", preprocessor),
        ("classifier", RandomForestClassifier(n_estimators=200, max_depth=4,
                                               class_weight="balanced", random_state=42)),
    ])

    # One row per student: leave-one-student-out is used for exploratory metrics.
    cv_predictions = cross_val_predict(model, X, y, cv=LeaveOneOut(), method="predict")
    cv_probabilities = cross_val_predict(model, X, y, cv=LeaveOneOut(), method="predict_proba")
    model.fit(X, y)
    if list(model.named_steps["classifier"].classes_) != CLASSES:
        raise SystemExit(f"Unexpected candidate classes: {model.named_steps['classifier'].classes_.tolist()}")
    if cv_probabilities.shape != (len(y), 2) or not (abs(cv_probabilities.sum(axis=1) - 1.0) < 1e-9).all():
        raise SystemExit("Probability validation failed for the exploratory model.")
    candidate_probabilities = model.predict_proba(X)
    if candidate_probabilities.shape != (len(y), 2) or not (abs(candidate_probabilities.sum(axis=1) - 1.0) < 1e-9).all():
        raise SystemExit("Saved candidate probability validation failed.")

    OUTPUT.mkdir(parents=True, exist_ok=True)
    artifact = OUTPUT / "random_forest.joblib"
    joblib.dump(model, artifact)
    prediction_frame = pd.DataFrame({"Student_ID": eligible.Student_ID, "Actual_Outcome": y,
                                     "LOO_Prediction": cv_predictions,
                                     "LOO_Needs_Practice_Probability": cv_probabilities[:, 0],
                                     "LOO_Successful_Probability": cv_probabilities[:, 1]})
    prediction_frame.to_csv(OUTPUT / "leave_one_student_out_predictions.csv", index=False)
    matrix = confusion_matrix(y, cv_predictions, labels=CLASSES)
    report = {
        "purpose": "Exploratory prototype only; not a validated or deployable model.",
        "trainedAt": datetime.now(timezone.utc).isoformat(),
        "sourceFeatureFile": str(FEATURE_PATH.relative_to(ROOT)),
        "sourceTargetFile": str(HISTORY_PATH.relative_to(ROOT)),
        "targetRule": "Notebook 04: per-student most frequent historical Outcome via value_counts().idxmax(); ties resolve by first observed outcome in row order.",
        "features": FEATURES,
        "classes": list(model.named_steps["classifier"].classes_),
        "allDominantTargetCounts": all_target_counts,
        "binaryTrainingClassCounts": y.value_counts().to_dict(),
        "excludedNonBinaryStudents": excluded.to_dict(orient="records"),
        "eligibleStudentCount": len(eligible),
        "studentsWithTiedHistoricalOutcomeCounts": tied_ids,
        "tieStudentCount": len(tied_ids),
        "validation": {
            "method": "Leave-one-student-out cross-validation; one aggregate row per student.",
            "accuracy": accuracy_score(y, cv_predictions),
            "precision_macro": precision_score(y, cv_predictions, labels=CLASSES, average="macro", zero_division=0),
            "recall_macro": recall_score(y, cv_predictions, labels=CLASSES, average="macro", zero_division=0),
            "f1_macro": f1_score(y, cv_predictions, labels=CLASSES, average="macro", zero_division=0),
            "confusionMatrixLabels": CLASSES,
            "confusionMatrix": matrix.tolist(),
            "classificationReport": classification_report(y, cv_predictions, labels=CLASSES, output_dict=True, zero_division=0),
        },
        "limitations": [
            "Only 14 student rows are eligible; this is insufficient for reliable generalization or the project training gate.",
            "Labels are derived from mixed historical lesson outcomes, not independent teacher-verified student outcomes.",
            "Six students have tied most-frequent outcomes; the source notebook resolves ties from row ordering.",
            "Selected lesson-history features may overlap with the historical outcome target, creating target leakage.",
            "This ten-feature model only predicts a general student-level class; it has no subject-specific output.",
            "This candidate is saved separately and is not wired into live inference.",
        ],
        "liveModelModified": False,
        "probabilityValidation": {
            "classes": list(model.named_steps["classifier"].classes_),
            "probabilityColumns": ["Needs Practice", "Successful"],
            "allCrossValidationRowsSumToOne": bool((abs(cv_probabilities.sum(axis=1) - 1.0) < 1e-9).all()),
            "allFittedCandidateRowsSumToOne": bool((abs(candidate_probabilities.sum(axis=1) - 1.0) < 1e-9).all()),
        },
        "candidateArtifact": str(artifact.relative_to(ROOT)),
    }
    (OUTPUT / "prototype_report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"candidateArtifact": report["candidateArtifact"],
                      "classCounts": report["binaryTrainingClassCounts"],
                      "excluded": report["excludedNonBinaryStudents"],
                      "metrics": report["validation"],
                      "tieStudentCount": report["tieStudentCount"],
                      "warning": "Exploratory only; historical labels are unverified and candidate is not deployed."}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
