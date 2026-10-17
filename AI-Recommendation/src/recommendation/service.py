"""Load the trained SmartPlay model and return student learning recommendations."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol, Sequence

import joblib
import pandas as pd


_RECOMMENDATION_COLUMNS = (
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
    "Teacher_Action",
)


class Classifier(Protocol):
    feature_names_in_: Sequence[str]

    def predict(self, features: pd.DataFrame) -> Sequence[object]: ...


class RecommendationService:
    """Predict a student's outcome and retrieve their validated learning path."""

    def __init__(
        self,
        model: Classifier,
        student_features: pd.DataFrame,
        learning_paths: pd.DataFrame,
    ) -> None:
        self._model = model
        self._student_features = student_features
        self._learning_paths = learning_paths

        model_features = getattr(model, "feature_names_in_", None)
        if model_features is None:
            raise ValueError("The trained model must expose feature_names_in_.")
        self._model_features = tuple(model_features)
        if not self._model_features:
            raise ValueError("The trained model does not define any input features.")

        self._require_columns(
            student_features,
            {"Student_ID", *self._model_features},
            "student features",
        )
        self._require_columns(
            learning_paths,
            {"Student_ID", *_RECOMMENDATION_COLUMNS},
            "learning paths",
        )

    @classmethod
    def from_project_root(
        cls, project_root: str | Path | None = None
    ) -> RecommendationService:
        """Load the production Random Forest and its deployment CSV files."""
        root = (
            Path(project_root)
            if project_root is not None
            else Path(__file__).resolve().parents[2]
        )
        model = joblib.load(root / "models" / "random_forest.joblib")
        student_features = pd.read_csv(
            root / "data" / "processed" / "student_features.csv"
        )
        learning_paths = pd.read_csv(
            root / "data" / "processed" / "validated_personalized_learning_paths.csv"
        )
        return cls(model, student_features, learning_paths)

    def predict_student(self, student_id: object) -> dict[str, object]:
        """Return a model outcome and probabilities, or a not-found status."""
        student_row = self._find_student(self._student_features, student_id)
        if student_row.empty:
            return {"Student_ID": student_id, "Status": "Student not found"}

        model_input = student_row.loc[:, self._model_features]
        prediction = self._model.predict(model_input)[0]
        if hasattr(prediction, "item"):
            prediction = prediction.item()

        result: dict[str, object] = {
            "Student_ID": student_id,
            "Predicted_Outcome": prediction,
            "Model": "Random Forest",
        }

        predict_proba = getattr(self._model, "predict_proba", None)
        if callable(predict_proba):
            classes = getattr(self._model, "classes_", None)
            if classes is None:
                raise ValueError(
                    "The model provides predict_proba but does not expose classes_."
                )
            probabilities = predict_proba(model_input)[0]
            result["Prediction_Probabilities"] = {
                str(label): round(float(probability), 4)
                for label, probability in zip(classes, probabilities)
            }

        return result

    def get_student_learning_path(
        self, student_id: object
    ) -> pd.DataFrame | None:
        """Return a student's validated path ordered by Path_Order."""
        learning_path = self._find_student(self._learning_paths, student_id)
        if learning_path.empty:
            return None
        return learning_path.sort_values("Path_Order").copy()

    def get_student_recommendation(self, student_id: object) -> dict[str, object]:
        """Combine the model prediction with the student's recommended lessons."""
        prediction = self.predict_student(student_id)
        if prediction.get("Status") == "Student not found":
            return {"Student_ID": student_id, "Status": "Student not found"}

        learning_path = self.get_student_learning_path(student_id)
        if learning_path is None:
            return {
                **prediction,
                "Status": "Prediction available, but learning path not found",
            }

        recommendations = learning_path.loc[
            :, _RECOMMENDATION_COLUMNS
        ].to_dict(orient="records")
        return {
            **prediction,
            "Status": "Recommendation available",
            "Number_of_Recommendations": len(recommendations),
            "Learning_Path": recommendations,
        }

    @staticmethod
    def _find_student(
        frame: pd.DataFrame, student_id: object
    ) -> pd.DataFrame:
        normalized_id = str(student_id).strip()
        matches = frame["Student_ID"].astype("string").str.strip() == normalized_id
        return frame.loc[matches]

    @staticmethod
    def _require_columns(
        frame: pd.DataFrame, required: set[str], description: str
    ) -> None:
        missing = required.difference(frame.columns)
        if missing:
            missing_columns = ", ".join(sorted(missing))
            raise ValueError(
                f"The {description} data is missing required columns: "
                f"{missing_columns}"
            )
