"""Live inference validation and conservative curriculum selection; no student CSV fallback."""
import math
import re
from numbers import Real

import pandas as pd


class ProfileError(ValueError):
    pass


AREA_PRACTICE_THRESHOLD = 80.0


def legacy_application_category(raw_outcome):
    """Compatibility view of old RF labels; Waiting means no binary learning result."""
    mapping = {"Successful": "Successful", "Needs Practice": "Needs Practice", "Waiting": "INSUFFICIENT_DATA"}
    if raw_outcome not in mapping:
        raise ProfileError("Unsupported model outcome")
    return mapping[raw_outcome]


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
    return {"Predicted_Outcome": outcome, "Model_Outcome_Compatibility": legacy_application_category(outcome),
            "Prediction_Probabilities": probabilities, "Model": "Random Forest"}


def normalize(text):
    return " ".join(re.findall(r"[a-z0-9]+", str(text).lower()))


def tokens(text):
    return set(normalize(text).split()) - {"and", "the", "of", "for", "skills", "activity", "assessment"}


def _need_diagnostic(need, curriculum_rows, approved_ids, grade, completed, known, difficulty_cap):
    """Explain why a measured area did not produce an approved curriculum activity."""
    domain = str(need.get("domain", "Unlabelled area"))
    domain_terms = tokens(domain)
    aligned = [row for row in curriculum_rows if domain_terms & tokens(" ".join(
        str(row.get(key, "")) for key in ("Subject", "Topic", "Competency", "Learning_Objective")
    ))]
    if not aligned:
        known_topics = sorted({str(row.get("Topic", "")).strip() for row in curriculum_rows if str(row.get("Topic", "")).strip()})
        lower = domain.lower()
        if "fruit" in lower:
            detail = "The catalog has a Plants lesson, but its objective only says to identify common plants; it does not explicitly cover naming fruits. Verify that the source lesson teaches fruit names or add an approved fruit-naming objective and activity."
        elif "bird" in lower:
            detail = "The Grade 1 catalog has no bird-identification topic or objective. Add an approved bird lesson, or provide curriculum evidence that an existing objective explicitly covers identifying birds."
        elif "song" in lower or "music" in lower or "lyric" in lower:
            detail = "The Grade 1 catalog has no music, singing, or lyrics objective. Add an approved songs activity, or provide curriculum evidence linking an existing objective to singing or lyrics."
        else:
            detail = "No curriculum subject, topic, competency, or objective directly matches this measured area. Add a curriculum-supported mapping with source evidence before recommending an activity."
        return {"Evidence_ID": need.get("assessment_id"), "Area": domain, "Reason": "MISSING_CURRICULUM_MAPPING", "Explanation": detail, "Catalog_Topics": known_topics}

    same_grade = [row for row in aligned if normalize(row.get("Grade", "")) == normalize(grade)]
    if not same_grade:
        return {"Evidence_ID": need.get("assessment_id"), "Area": domain, "Reason": "GRADE_MISMATCH", "Explanation": f"Related catalog content exists, but not for {grade}. A teacher-approved curriculum entry for this grade is required."}
    approved = [row for row in same_grade if str(row.get("Lesson_ID", "")) in approved_ids]
    if not approved:
        ids = sorted({str(row.get("Lesson_ID", "")) for row in same_grade})
        return {"Evidence_ID": need.get("assessment_id"), "Area": domain, "Reason": "ACTIVITY_NOT_VALIDATED", "Explanation": "Related same-grade catalog lessons exist, but their identifiers are absent from the validated pathway list.", "Catalog_Lesson_IDs": ids}
    not_completed = [row for row in approved if str(row.get("Lesson_ID", "")) not in completed]
    if not not_completed:
        return {"Evidence_ID": need.get("assessment_id"), "Area": domain, "Reason": "ACTIVITIES_ALREADY_COMPLETED", "Explanation": "All matching validated activities are already in the student's completed lesson history."}
    within_level = [row for row in not_completed if {"beginner": 1, "basic": 2, "intermediate": 3}.get(normalize(row.get("Difficulty_Level", "")), 99) <= difficulty_cap]
    if not within_level:
        return {"Evidence_ID": need.get("assessment_id"), "Area": domain, "Reason": "DIFFICULTY_NOT_ELIGIBLE", "Explanation": "Matching activities exceed the maximum difficulty allowed for this prediction outcome."}
    missing_prerequisite = [row for row in within_level if normalize(row.get("Required_Skills", "")) and normalize(row.get("Required_Skills", "")) not in known]
    if len(missing_prerequisite) == len(within_level):
        skills = sorted({str(row.get("Required_Skills", "")).strip() for row in missing_prerequisite if str(row.get("Required_Skills", "")).strip()})
        return {"Evidence_ID": need.get("assessment_id"), "Area": domain, "Reason": "PREREQUISITE_NOT_CONFIRMED", "Explanation": "The catalog requires a prerequisite not present in the teacher-recorded demonstrated skills.", "Required_Skills": skills}
    return {"Evidence_ID": need.get("assessment_id"), "Area": domain, "Reason": "NO_ELIGIBLE_ACTIVITY", "Explanation": "Related content exists, but no unique, complete, validated activity passed every eligibility check."}


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
    application_outcome = profile.get("application_outcome")
    if application_outcome == "Successful":
        return {**prediction, "Application_Outcome": application_outcome,
                "Verified_Performance_Percentage": profile.get("verified_performance_percentage"),
                "Path_Type": "NONE", "Status": "Verified overall performance is at least 80%; no remedial pathway was generated.",
                "Number_of_Recommendations": 0, "Learning_Path": [], "Unmatched_Needs": []}
    if profile.get("pathway_mode") == "THREE_LESSON_MILESTONE" and application_outcome is None and outcome != "Needs Practice":
        return select_common_pathway(curriculum, validated_paths, profile, prediction)
    difficulty_outcome = application_outcome or outcome
    if difficulty_outcome not in caps:
        raise ProfileError("Unsupported application outcome")
    required = {"Lesson_ID", "Grade", "Subject", "Topic", "Competency", "Learning_Objective", "Difficulty_Level", "Required_Skills", "Classroom_Activities"}
    if not required.issubset(curriculum.columns) or "Recommended_Lesson_ID" not in validated_paths.columns:
        raise ProfileError("Curriculum data is missing required columns")
    approved_ids = set(validated_paths["Recommended_Lesson_ID"].dropna().astype(str))
    known = {normalize(skill) for skill in profile["demonstrated_skills"]}
    completed = set(profile["completed_lesson_ids"])
    difficulties = {"beginner": 1, "basic": 2, "intermediate": 3}
    candidates = []
    unmatched = []
    # These context fields affect curriculum ranking only, never the model vector.
    context = profile.get("teacher_context", [])
    note_terms = tokens(" ".join(str(note.get("next_step", "")) + " " + str(note.get("improvement", "")) for note in context))
    difficulty_terms = tokens(profile.get("recorded_difficulty") or "") - {"difficulty", "none", "identified"}
    trend = profile.get("measured_improvement")
    # Conflicting duplicate IDs are not safe references.
    duplicate_ids = set(curriculum.loc[curriculum.duplicated("Lesson_ID", keep=False), "Lesson_ID"])
    catalog_rows = curriculum.fillna("").to_dict("records")
    for row in catalog_rows:
        lesson_id = str(row["Lesson_ID"])
        if lesson_id in duplicate_ids or lesson_id not in approved_ids or lesson_id in completed:
            continue
        if normalize(row["Grade"]) != normalize(profile["grade"]):
            continue
        difficulty = difficulties.get(normalize(row["Difficulty_Level"]))
        if difficulty is None or difficulty > caps[difficulty_outcome]:
            continue
        prerequisite = normalize(row["Required_Skills"])
        if prerequisite and prerequisite not in known:
            continue
        if not all(str(row[key]).strip() for key in ["Subject", "Topic", "Learning_Objective", "Classroom_Activities"]):
            continue
        matches = []
        for need in profile["needs"]:
            if application_outcome == "Needs Practice" and float(need["score_percentage"]) >= AREA_PRACTICE_THRESHOLD:
                continue
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
            "Selection_Reason": f"{need['domain']} evidence is {score:g}%; curriculum terms match; prerequisite confirmed; {row['Difficulty_Level']} is eligible for {difficulty_outcome}.",
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
    weak_needs = [need for need in profile["needs"] if application_outcome != "Needs Practice" or float(need["score_percentage"]) < AREA_PRACTICE_THRESHOLD]
    if not selected and weak_needs:
        unmatched = [_need_diagnostic(need, catalog_rows, approved_ids, profile["grade"], completed, known, caps[difficulty_outcome]) for need in weak_needs]
    status = "Recommendation available" if selected else "No individual learning area is below 80%; no targeted pathway was generated." if not weak_needs else "No pathway saved. " + " ".join(
        f"{item['Area']}: {item['Explanation']}" for item in unmatched[:3]
    )
    return {**prediction, "Application_Outcome": application_outcome,
            "Verified_Performance_Percentage": profile.get("verified_performance_percentage"),
            "Path_Type": "PERSONALIZED", "Status": status,
            "Unmatched_Needs": unmatched, "Number_of_Recommendations": len(selected), "Learning_Path": selected}


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
