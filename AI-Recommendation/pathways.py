"""Live inference validation and conservative curriculum selection; no student CSV fallback."""
import math
import re
from numbers import Real

import pandas as pd


class ProfileError(ValueError):
    pass


def validate_features(model, values):
    names = list(model.feature_names_in_)
    if set(values) != set(names):
        raise ProfileError(f"Expected exactly {len(names)} features; missing: {sorted(set(names) - set(values))}; unexpected: {sorted(set(values) - set(names))}")
    encoder = model.named_steps["preprocessor"].named_transformers_["categorical"]
    categorical = next(columns for name, _, columns in model.named_steps["preprocessor"].transformers_ if name == "categorical")
    categories = dict(zip(categorical, encoder.categories_))
    for name in names:
        value = values[name]
        if name in categories:
            if not isinstance(value, str) or value not in categories[name]:
                raise ProfileError(f"Invalid category for {name}")
        elif isinstance(value, bool) or not isinstance(value, Real) or not math.isfinite(value):
            raise ProfileError(f"{name} must be a finite number")
        elif name != "Test_Improvement" and value < 0:
            raise ProfileError(f"{name} cannot be negative")
    for name in ["Average_Score", "Completion_Rate", "Pre_Test", "Post_Test", "Unit_Test", "Monthly_Test", "Term_Result", "Motor_Skills_Score", "Creativity_Score", "Interactive_Engagement", "Historical_Completion_Rate"]:
        if values[name] > 100:
            raise ProfileError(f"{name} must be on the training 0–100 scale")
    for name in ["Total_Activities", "Historical_Lessons", "Completed_Historical_Lessons"]:
        if int(values[name]) != values[name]:
            raise ProfileError(f"{name} must be a count")
    if values["Total_Activities"] < 1 or values["Historical_Lessons"] < 1:
        raise ProfileError("Insufficient activity or historical-lesson data")
    if values["Completed_Historical_Lessons"] > values["Historical_Lessons"]:
        raise ProfileError("Completed historical lessons exceed total lessons")
    levels = {"Low": 1, "Medium": 2, "High": 3}
    for category, score in [("Participation", "Participation_Score"), ("Attention_Level", "Attention_Score"), ("Confidence", "Confidence_Score")]:
        if values[score] != levels[values[category]]:
            raise ProfileError(f"{score} disagrees with the notebook encoding")
    collaboration = {"Fair": 1, "Needs improvement": 2, "Good": 3, "Excellent": 4}
    if values["Collaboration_Score"] != collaboration[values["Collaboration"]]:
        raise ProfileError("Collaboration_Score disagrees with the notebook encoding")
    return pd.DataFrame([[values[name] for name in names]], columns=names)


def predict(model, values):
    frame = validate_features(model, values)
    outcome = str(model.predict(frame)[0])
    probabilities = {str(label): float(value) for label, value in zip(model.classes_, model.predict_proba(frame)[0])}
    return {"Predicted_Outcome": outcome, "Prediction_Probabilities": probabilities, "Model": "Random Forest"}


def normalize(text):
    return " ".join(re.findall(r"[a-z0-9]+", str(text).lower()))


def tokens(text):
    return set(normalize(text).split()) - {"and", "the", "of", "for", "skills", "activity", "assessment"}


def select_pathway(curriculum, validated_paths, profile, prediction):
    """Eligibility: validated lesson ID, grade, evidenced need, prerequisite confirmation.

    Rank by weaker assessed area, topic/competency overlap, then easier difficulty,
    then lesson ID. Outcome controls a documented maximum difficulty, not suitability
    by itself. Free-text prerequisites require exact normalized teacher confirmation.
    """
    outcome = prediction["Predicted_Outcome"]
    caps = {"Waiting": 1, "Needs Practice": 2, "Successful": 3}
    if outcome not in caps:
        raise ProfileError("Unsupported model outcome")
    if profile.get("pathway_mode") == "THREE_LESSON_MILESTONE" and outcome != "Needs Practice":
        return select_common_pathway(curriculum, validated_paths, profile, prediction)
    required = {"Lesson_ID", "Grade", "Subject", "Topic", "Competency", "Learning_Objective", "Difficulty_Level", "Required_Skills", "Classroom_Activities"}
    if not required.issubset(curriculum.columns) or "Recommended_Lesson_ID" not in validated_paths.columns:
        raise ProfileError("Curriculum data is missing required columns")
    approved_ids = set(validated_paths["Recommended_Lesson_ID"].dropna().astype(str))
    known = {normalize(skill) for skill in profile["demonstrated_skills"]}
    completed = set(profile["completed_lesson_ids"])
    difficulties = {"beginner": 1, "basic": 2, "intermediate": 3}
    candidates = []
    # These context fields affect curriculum ranking only, never the model vector.
    context = profile.get("teacher_context", [])
    note_terms = tokens(" ".join(str(note.get("next_step", "")) + " " + str(note.get("improvement", "")) for note in context))
    difficulty_terms = tokens(profile.get("recorded_difficulty") or "") - {"difficulty", "none", "identified"}
    trend = profile.get("measured_improvement")
    # Conflicting duplicate IDs are not safe references.
    duplicate_ids = set(curriculum.loc[curriculum.duplicated("Lesson_ID", keep=False), "Lesson_ID"])
    for row in curriculum.fillna("").to_dict("records"):
        lesson_id = str(row["Lesson_ID"])
        if lesson_id in duplicate_ids or lesson_id not in approved_ids or lesson_id in completed:
            continue
        if normalize(row["Grade"]) != normalize(profile["grade"]):
            continue
        difficulty = difficulties.get(normalize(row["Difficulty_Level"]))
        if difficulty is None or difficulty > caps[outcome]:
            continue
        prerequisite = normalize(row["Required_Skills"])
        if prerequisite and prerequisite not in known:
            continue
        if not all(str(row[key]).strip() for key in ["Subject", "Topic", "Learning_Objective", "Classroom_Activities"]):
            continue
        matches = []
        for need in profile["needs"]:
            overlap = len(tokens(need["domain"]) & tokens(" ".join(str(row[key]) for key in ["Subject", "Topic", "Competency", "Learning_Objective"])))
            if overlap:
                matches.append((float(need["score_percentage"]), -overlap, need))
        if not matches:
            continue
        score, negative_overlap, need = min(matches, key=lambda match: (match[0], match[1], match[2]["assessment_id"]))
        item = {
            "Recommended_Lesson_ID": lesson_id, "Recommended_Subject": row["Subject"],
            "Recommended_Topic": row["Topic"], "Recommended_Competency": row["Competency"],
            "Learning_Objective": row["Learning_Objective"], "Difficulty_Level": row["Difficulty_Level"],
            "Required_Skills": row["Required_Skills"], "Classroom_Activities": row["Classroom_Activities"],
            "Evidence_ID": need["assessment_id"], "Evidence_Score": score,
            "Selection_Reason": f"{need['domain']} evidence is {score:g}%; curriculum terms match; prerequisite confirmed; {row['Difficulty_Level']} is eligible for {outcome}.",
        }
        curriculum_terms = tokens(" ".join(str(row[key]) for key in ["Subject", "Topic", "Competency", "Learning_Objective"]))
        difficulty_match = len(curriculum_terms & difficulty_terms)
        feedback_match = len(curriculum_terms & note_terms)
        strength_match = len(curriculum_terms & tokens(" ".join(profile["demonstrated_skills"])))
        # Declining measured performance prefers the easier eligible activity;
        # improving performance may progress within the RF outcome's difficulty cap.
        activity_trend = need.get("score_change") if need.get("score_change") is not None else trend
        trend_rank = -difficulty if activity_trend is not None and activity_trend > 0 else difficulty
        if difficulty_match or feedback_match:
            sources = ", ".join(note["evidence_id"] for note in context if tokens(note.get("next_step", "") + " " + note.get("improvement", "")) & curriculum_terms)
            item["Selection_Reason"] += f" Recorded difficulty overlap: {difficulty_match}; teacher-context overlap: {feedback_match}."
            if sources: item["Selection_Reason"] += f" Feedback evidence: {sources}."
        if need.get("score_change") is not None: item["Selection_Reason"] += f" Measured change in this learning area: {need['score_change']:g} points."
        if trend is not None: item["Selection_Reason"] += f" Measured pre/post improvement: {trend:g} points."
        candidates.append(((score, negative_overlap, -difficulty_match, -feedback_match, trend_rank, -strength_match, lesson_id), item))
    candidates.sort(key=lambda candidate: candidate[0])
    selected = [dict(item, Path_Order=index + 1) for index, (_, item) in enumerate(candidates[:profile["limit"]])]
    return {**prediction, "Path_Type": "PERSONALIZED", "Status": "Recommendation available" if selected else "No suitable validated activity",
            "Number_of_Recommendations": len(selected), "Learning_Path": selected}


def select_common_pathway(curriculum, validated_paths, profile, prediction):
    """Return a shared, grade-level foundation sequence for milestone outcomes other than Needs Practice."""
    required = {"Lesson_ID", "Grade", "Subject", "Topic", "Competency", "Learning_Objective", "Difficulty_Level", "Required_Skills", "Classroom_Activities"}
    if not required.issubset(curriculum.columns) or "Recommended_Lesson_ID" not in validated_paths.columns:
        raise ProfileError("Curriculum data is missing required columns")
    approved_ids = set(validated_paths["Recommended_Lesson_ID"].dropna().astype(str))
    completed = set(profile["completed_lesson_ids"])
    known = {normalize(skill) for skill in profile["demonstrated_skills"]}
    duplicate_ids = set(curriculum.loc[curriculum.duplicated("Lesson_ID", keep=False), "Lesson_ID"])
    evidence_id = profile["needs"][0]["assessment_id"]
    selected = []
    for row in curriculum.fillna("").to_dict("records"):
        lesson_id = str(row["Lesson_ID"])
        if lesson_id in duplicate_ids or lesson_id not in approved_ids or lesson_id in completed:
            continue
        if normalize(row["Grade"]) != normalize(profile["grade"]):
            continue
        if normalize(row["Difficulty_Level"]) != "beginner":
            continue
        prerequisite = normalize(row["Required_Skills"])
        if prerequisite and prerequisite not in known:
            continue
        if not all(str(row[key]).strip() for key in ["Subject", "Topic", "Learning_Objective", "Classroom_Activities"]):
            continue
        selected.append({
            "Recommended_Lesson_ID": lesson_id, "Recommended_Subject": row["Subject"],
            "Recommended_Topic": row["Topic"], "Recommended_Competency": row["Competency"],
            "Learning_Objective": row["Learning_Objective"], "Difficulty_Level": row["Difficulty_Level"],
            "Required_Skills": row["Required_Skills"], "Classroom_Activities": row["Classroom_Activities"],
            "Evidence_ID": evidence_id,
            "Selection_Reason": f"Shared Grade {profile['grade']} foundation pathway for the Random Forest {prediction['Predicted_Outcome']} outcome; its prerequisite is already demonstrated, and this is not an individualized practice recommendation.",
        })
        if len(selected) >= profile["limit"]:
            break
    selected = [dict(item, Path_Order=index + 1) for index, item in enumerate(selected)]
    return {**prediction, "Path_Type": "COMMON", "Status": "Recommendation available" if selected else "No suitable validated activity",
            "Number_of_Recommendations": len(selected), "Learning_Path": selected}
