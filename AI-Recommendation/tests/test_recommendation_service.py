import numpy as np
import pandas as pd

from src.recommendation import RecommendationService


class DummyClassifier:
    feature_names_in_ = ["score"]
    classes_ = np.array(["Needs Practice", "Successful"])

    def predict(self, features: pd.DataFrame) -> np.ndarray:
        return np.array(["Successful"] * len(features))

    def predict_proba(self, features: pd.DataFrame) -> np.ndarray:
        return np.tile([0.2, 0.8], (len(features), 1))


def make_service(learning_paths: pd.DataFrame | None = None) -> RecommendationService:
    student_features = pd.DataFrame(
        {"Student_ID": [" 101 "], "score": [0.9]}
    )
    if learning_paths is None:
        learning_paths = pd.DataFrame(
            [
                {
                    "Student_ID": "101",
                    "Path_Order": 2,
                    "Learning_Stage": "Practice",
                    "Recommended_Lesson_ID": "L2",
                    "Recommended_Subject": "English",
                    "Recommended_Topic": "Speaking",
                    "Recommended_Competency": "Vocabulary",
                    "Learning_Objective": "Use new words",
                    "Difficulty_Level": "Easy",
                    "Required_Skills": "Listening",
                    "Classroom_Activities": "Pair work",
                    "Alignment_Score": 0.8,
                    "Performance_Level": "Developing",
                    "Overall_Performance": 0.6,
                    "Teaching_Strategy": "Guided practice",
                    "Recommended_Duration": 20,
                    "Teacher_Action": "Review progress",
                },
                {
                    "Student_ID": "101",
                    "Path_Order": 1,
                    "Learning_Stage": "Foundation",
                    "Recommended_Lesson_ID": "L1",
                    "Recommended_Subject": "English",
                    "Recommended_Topic": "Listening",
                    "Recommended_Competency": "Comprehension",
                    "Learning_Objective": "Follow instructions",
                    "Difficulty_Level": "Easy",
                    "Required_Skills": "Attention",
                    "Classroom_Activities": "Listen and respond",
                    "Alignment_Score": 0.9,
                    "Performance_Level": "Developing",
                    "Overall_Performance": 0.6,
                    "Teaching_Strategy": "Modeling",
                    "Recommended_Duration": 15,
                    "Teacher_Action": "Check understanding",
                },
            ]
        )
    return RecommendationService(
        DummyClassifier(), student_features, learning_paths
    )


def test_recommendation_combines_prediction_and_sorted_learning_path() -> None:
    recommendation = make_service().get_student_recommendation("101")

    assert recommendation["Status"] == "Recommendation available"
    assert recommendation["Predicted_Outcome"] == "Successful"
    assert recommendation["Prediction_Probabilities"] == {
        "Needs Practice": 0.2,
        "Successful": 0.8,
    }
    assert recommendation["Number_of_Recommendations"] == 2
    learning_path = recommendation["Learning_Path"]
    assert [item["Path_Order"] for item in learning_path] == [1, 2]
    assert "Student_ID" not in learning_path[0]


def test_unknown_student_returns_not_found_status() -> None:
    recommendation = make_service().get_student_recommendation("999")

    assert recommendation == {
        "Student_ID": "999",
        "Status": "Student not found",
    }


def test_prediction_is_returned_when_learning_path_is_missing() -> None:
    recommendation = make_service(pd.DataFrame(columns=[
        "Student_ID",
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
    ])).get_student_recommendation("101")

    assert recommendation["Predicted_Outcome"] == "Successful"
    assert recommendation["Status"] == (
        "Prediction available, but learning path not found"
    )
