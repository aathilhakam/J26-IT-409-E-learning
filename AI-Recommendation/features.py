"""Notebook 03 formulas extracted for reproducible, training-compatible profile inputs.

Inputs use the actual cleaned source schemas. Caller must supply measured values;
missing observations/assessments/history are errors, never filled from student CSVs.
"""
import pandas as pd
from pathways import ProfileError

ASSESSMENTS = ["Pre_Test", "Post_Test", "Unit_Test", "Monthly_Test", "Term_Result", "Motor_Skills_Score", "Creativity_Score", "Interactive_Engagement"]
CATEGORIES = ["Participation", "Attention_Level", "Confidence", "Collaboration", "Learning_Difficulty", "Learning_Style"]


def build_features(activities, assessments, observation, history):
    if not activities or not assessments or not observation or not history:
        raise ProfileError("Measured activities, assessments, teacher observation and historical lessons are all required")
    a, e, h = pd.DataFrame(activities), pd.DataFrame(assessments), pd.DataFrame(history)
    required = [(a, ["Activity_ID", "Score", "Attempts", "Time_Taken_Min", "Completion_Status"]), (e, ASSESSMENTS), (h, ["Lesson_ID", "Sequence_No", "Status"])]
    if any(not set(columns).issubset(frame.columns) or frame[columns].isna().any().any() for frame, columns in required):
        raise ProfileError("Source records lack required measured fields")
    if any(name not in observation or observation[name] is None for name in CATEGORIES):
        raise ProfileError("Teacher observation is incomplete")
    if not a.Completion_Status.isin(["Completed", "In Progress"]).all() or not h.Status.isin(["Completed", "Not Started", "In Progress"]).all():
        raise ProfileError("Use the cleaned training completion-status categories")
    for frame, columns in [(a, ["Score", "Attempts", "Time_Taken_Min"]), (e, ASSESSMENTS), (h, ["Sequence_No"])]:
        for column in columns:
            if not pd.api.types.is_numeric_dtype(frame[column]) or pd.api.types.is_bool_dtype(frame[column]):
                raise ProfileError(f"Source {column} must contain measured numbers")
    try:
        result = {
            "Average_Score": float(a.Score.mean()), "Average_Attempts": float(a.Attempts.mean()),
            "Average_Time_Taken": float(a.Time_Taken_Min.mean()), "Total_Activities": int(a.Activity_ID.count()),
            "Completion_Rate": float(a.Completion_Status.eq("Completed").mean() * 100),
            **{name: float(e[name].mean()) for name in ASSESSMENTS},
            "Test_Improvement": float((e.Post_Test - e.Pre_Test).mean()),
            **{name: observation[name] for name in CATEGORIES},
            "Historical_Lessons": int(h.Lesson_ID.count()), "Average_Sequence_No": float(h.Sequence_No.mean()),
            "Completed_Historical_Lessons": int(h.Status.eq("Completed").sum()),
            "Historical_Completion_Rate": float(h.Status.eq("Completed").mean() * 100),
        }
        levels = {"Low": 1, "Medium": 2, "High": 3}
        result.update(Participation_Score=levels[observation["Participation"]], Attention_Score=levels[observation["Attention_Level"]], Confidence_Score=levels[observation["Confidence"]],
                      Collaboration_Score={"Fair": 1, "Needs improvement": 2, "Good": 3, "Excellent": 4}[observation["Collaboration"]])
        return result
    except (KeyError, TypeError, ValueError) as error:
        raise ProfileError("Source data does not follow the training feature schema") from error
