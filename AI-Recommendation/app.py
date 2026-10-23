
from pathlib import Path
from typing import Literal
import json

import joblib
import pandas as pd
from fastapi import FastAPI, HTTPException, Depends, Header
import os
import secrets

# -------------------------------------------------
# 1. Project paths
# -------------------------------------------------

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data" / "processed"
MODEL_DIR = BASE_DIR / "models"

MODEL_FILE = MODEL_DIR / "random_forest.joblib"
PROTOTYPE_MODEL_FILE = MODEL_DIR / "candidates" / "binary-10-feature-prototype" / "random_forest.joblib"
STUDENT_FILE = DATA_DIR / "student_features.csv"
LEARNING_PATH_FILE = (
    DATA_DIR / "validated_personalized_learning_paths.csv"
)

# -------------------------------------------------
# 2. Load the trained Random Forest model
# -------------------------------------------------

# Only load model files created by trusted sources.
model = joblib.load(MODEL_FILE)

if not hasattr(model, "feature_names_in_"):
    raise RuntimeError(
        "The trained model does not contain feature names."
    )

MODEL_FEATURES = list(model.feature_names_in_)

PROTOTYPE_FEATURES = [
    "Average_Attempts", "Total_Activities", "Completion_Rate",
    "Participation", "Attention_Level", "Collaboration", "Learning_Difficulty",
    "Historical_Lessons", "Completed_Historical_Lessons", "Historical_Completion_Rate",
]
PROTOTYPE_CLASSES = ["Needs Practice", "Successful"]
prototype_model = None
if PROTOTYPE_MODEL_FILE.is_file():
    prototype_model = joblib.load(PROTOTYPE_MODEL_FILE)
    if list(getattr(prototype_model, "feature_names_in_", [])) != PROTOTYPE_FEATURES:
        raise RuntimeError("Binary prototype artifact does not match the approved ten-feature prototype schema.")
    if list(getattr(prototype_model, "classes_", [])) != PROTOTYPE_CLASSES:
        raise RuntimeError("Binary prototype artifact does not contain exactly the two expected classes.")

# -------------------------------------------------
# 3. Load student datasets
# -------------------------------------------------

student_features = pd.read_csv(
    STUDENT_FILE,
    dtype={"Student_ID": str}
)

validated_learning_paths = pd.read_csv(
    LEARNING_PATH_FILE,
    dtype={"Student_ID": str}
)

missing_features = [
    feature
    for feature in MODEL_FEATURES
    if feature not in student_features.columns
]

if missing_features:
    raise RuntimeError(
        f"Missing model features: {missing_features}"
    )

print("Random Forest model loaded successfully")
print("Number of features:", len(MODEL_FEATURES))
print("Students available:", len(student_features))

# -------------------------------------------------
# 4. FastAPI application
# -------------------------------------------------

app = FastAPI(
    title="SmartPlay AI Recommendation API",
    version="1.0.0"
)

@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "model": "Random Forest",
        "features": len(MODEL_FEATURES),
        "binary_prototype_available": prototype_model is not None,
    }

# -------------------------------------------------
# 5. Generate a personalized recommendation
# -------------------------------------------------

@app.get("/api/recommendations/{student_id}")
def get_student_recommendation(student_id: str):

    student_id = student_id.strip()

    # Find the student in the deployment dataset
    student_row = student_features[
        student_features["Student_ID"].str.strip()
        == student_id
    ]

    if student_row.empty:
        raise HTTPException(
            status_code=404,
            detail=f"Student {student_id} not found"
        )

    # Use the exact feature names and order
    # stored in the trained model
    X_student = student_row[MODEL_FEATURES].iloc[[0]]

    if X_student.isnull().any().any():
        raise HTTPException(
            status_code=422,
            detail="Student features contain missing values"
        )

    # Random Forest prediction
    prediction = model.predict(X_student)[0]

    if hasattr(prediction, "item"):
        prediction = prediction.item()

    result = {
        "Student_ID": student_id,
        "Predicted_Outcome": prediction,
        "Model": "Random Forest"
    }

    # Prediction probabilities
    if hasattr(model, "predict_proba"):
        probabilities = model.predict_proba(X_student)[0]

        result["Prediction_Probabilities"] = {
            str(label): round(float(probability), 4)
            for label, probability in zip(
                model.classes_,
                probabilities
            )
        }

    # Retrieve validated personalized learning paths
    student_path = validated_learning_paths[
        validated_learning_paths["Student_ID"].str.strip()
        == student_id
    ].copy()

    if student_path.empty:
        return {
            **result,
            "Status": (
                "Prediction available, "
                "but learning path not found"
            ),
            "Number_of_Recommendations": 0,
            "Learning_Path": []
        }

    if "Path_Order" in student_path.columns:
        student_path = student_path.sort_values("Path_Order")

    recommendation_columns = [
        "Path_Order",
        "Learning_Stage",
        "Recommended_Lesson_ID",
        "Recommended_Subject",
        "Recommended_Topic",
        "Recommended_Competency",
        "Learning_Objective",
        "Difficulty_Level",
        "Required_Skills",
        "Classroom_Activities",
        "Alignment_Score",
        "Performance_Level",
        "Overall_Performance",
        "Teaching_Strategy",
        "Recommended_Duration",
        "Teacher_Action"
    ]

    missing_columns = [
        column
        for column in recommendation_columns
        if column not in student_path.columns
    ]

    if missing_columns:
        raise HTTPException(
            status_code=500,
            detail=f"Missing learning path columns: {missing_columns}"
        )

    # Convert pandas values into JSON-compatible values,
    # including converting missing values to null.
    recommendations = json.loads(
        student_path[recommendation_columns].to_json(
            orient="records"
        )
    )

    return {
        **result,
        "Status": "Recommendation available",
        "Number_of_Recommendations": len(recommendations),
        "Learning_Path": recommendations
    }

# Live requests are separate from the explicitly dataset-based GET endpoint above.
from pydantic import BaseModel, ConfigDict, Field
from pathways import ProfileError, predict, select_pathway


class Need(BaseModel):
    model_config = ConfigDict(extra="forbid")
    assessment_id: str = Field(min_length=1, max_length=180)
    domain: str = Field(min_length=1, max_length=180)
    score_percentage: float = Field(ge=0, le=100, allow_inf_nan=False)
    score_change: float | None = Field(default=None, ge=-100, le=100, allow_inf_nan=False)


class TeacherContext(BaseModel):
    model_config = ConfigDict(extra="forbid")
    evidence_id: str = Field(min_length=1, max_length=300)
    improvement: str = Field(default="", max_length=2000)
    next_step: str = Field(default="", max_length=2000)


class LiveProfile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    features: dict
    grade: str = Field(min_length=1, max_length=60)
    needs: list[Need] = Field(min_length=1, max_length=200)
    demonstrated_skills: list[str] = Field(default_factory=list, max_length=200)
    completed_lesson_ids: list[str] = Field(default_factory=list, max_length=1000)
    limit: int = Field(default=3, ge=1, le=10)
    teacher_context: list[TeacherContext] = Field(default_factory=list, max_length=200)
    recorded_difficulty: str | None = Field(default=None, max_length=180)
    measured_improvement: float | None = Field(default=None, ge=-100, le=100, allow_inf_nan=False)
    pathway_mode: Literal["THREE_LESSON_MILESTONE"] | None = None
    # Calculated by the authenticated Spring service from persisted valid score records.
    verified_performance_percentage: float | None = Field(default=None, ge=0, le=100, allow_inf_nan=False)
    application_outcome: Literal["Successful", "Needs Practice"] | None = None


def load_inference_token():
    token = os.environ.get("SMARTPLAY_INFERENCE_TOKEN", "").strip()
    if token:
        return token
    # Local development shares the backend's ignored .env file so the two
    # services cannot accidentally start with different inference tokens.
    backend_env = BASE_DIR.parent / "backend" / ".env"
    try:
        for line in backend_env.read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "SMARTPLAY_INFERENCE_TOKEN":
                return value.strip().strip("\"'")
    except OSError:
        pass
    return ""


INFERENCE_TOKEN = load_inference_token()


def require_inference_token(authorization: str | None = Header(default=None)):
    if INFERENCE_TOKEN and not secrets.compare_digest(authorization or "", "Bearer " + INFERENCE_TOKEN):
        raise HTTPException(status_code=401, detail="Authorized backend inference required")


@app.post("/api/pathways", dependencies=[Depends(require_inference_token)])
def live_pathway(profile: LiveProfile):
    try:
        prediction = predict(model, profile.features)
        catalog = pd.read_csv(DATA_DIR / "curriculum_clean.csv")
        result = select_pathway(catalog, validated_learning_paths, profile.model_dump(), prediction)
        # Hash identifies the unchanged model; probabilities are not accuracy.
        import hashlib
        result["Model_Version"] = hashlib.sha256(MODEL_FILE.read_bytes()).hexdigest()
        return result
    except ProfileError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


class BinaryPrototypeProfile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    features: dict


@app.post("/api/prototype/predict", dependencies=[Depends(require_inference_token)])
def predict_binary_prototype(profile: BinaryPrototypeProfile):
    """Predict with the isolated ten-feature candidate; never create pathways."""
    if prototype_model is None:
        raise HTTPException(status_code=503, detail="The exploratory binary prototype artifact is not installed.")
    if set(profile.features) != set(PROTOTYPE_FEATURES):
        missing = sorted(set(PROTOTYPE_FEATURES) - set(profile.features))
        unexpected = sorted(set(profile.features) - set(PROTOTYPE_FEATURES))
        raise HTTPException(status_code=422, detail=f"Prototype feature mismatch; missing: {missing}; unexpected: {unexpected}")
    frame = pd.DataFrame([[profile.features[name] for name in PROTOTYPE_FEATURES]], columns=PROTOTYPE_FEATURES)
    try:
        prediction = str(prototype_model.predict(frame)[0])
        probabilities = prototype_model.predict_proba(frame)[0]
    except (TypeError, ValueError) as error:
        raise HTTPException(status_code=422, detail=f"Prototype features are invalid: {error}") from error
    if prediction not in PROTOTYPE_CLASSES or len(probabilities) != 2:
        raise HTTPException(status_code=500, detail="Prototype returned an unsupported class response.")
    values = {str(label): float(probability) for label, probability in zip(prototype_model.classes_, probabilities)}
    if not all(0 <= value <= 1 for value in values.values()) or abs(sum(values.values()) - 1.0) > 1e-9:
        raise HTTPException(status_code=500, detail="Prototype returned invalid class probabilities.")
    import hashlib
    from datetime import datetime, timezone
    return {
        "predictedClass": prediction,
        "probabilities": values,
        "modelVersion": hashlib.sha256(PROTOTYPE_MODEL_FILE.read_bytes()).hexdigest(),
        "predictedAt": datetime.now(timezone.utc).isoformat(),
        "featureCount": len(PROTOTYPE_FEATURES),
        "features": profile.features,
        "status": "EXPLORATORY_PROTOTYPE",
        "warning": "Exploratory only: trained on 14 historical students with unverified, history-derived labels. Not validated for student decisions or subject-specific practice.",
    }


class SourceProfile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    activities: list[dict] = Field(min_length=1, max_length=10000)
    assessments: list[dict] = Field(min_length=1, max_length=10000)
    observation: dict
    historical_lessons: list[dict] = Field(min_length=1, max_length=10000)
    grade: str = Field(min_length=1, max_length=60)
    needs: list[Need] = Field(min_length=1, max_length=200)
    demonstrated_skills: list[str] = Field(default_factory=list, max_length=200)
    completed_lesson_ids: list[str] = Field(default_factory=list, max_length=1000)
    limit: int = Field(default=3, ge=1, le=10)


@app.post("/api/pathways/from-records")
def live_pathway_from_records(profile: SourceProfile):
    from features import build_features
    try:
        values = build_features(profile.activities, profile.assessments, profile.observation, profile.historical_lessons)
    except ProfileError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    return live_pathway(LiveProfile(features=values, grade=profile.grade, needs=profile.needs,
                                   demonstrated_skills=profile.demonstrated_skills, completed_lesson_ids=profile.completed_lesson_ids, limit=profile.limit))
