"""Fixtures are unchanged real dataset rows, not feature edits to force predictions."""
import hashlib
import sys
from pathlib import Path

import pandas as pd
import pytest
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import app as service
from pathways import ProfileError, predict, select_pathway, validate_features

# Tests exercise the API contract directly and should not depend on the
# developer's local backend .env inference token.
service.INFERENCE_TOKEN = ""
client = TestClient(service.app)


def features(student="ST001"):
    return service.student_features.set_index("Student_ID").loc[student, service.MODEL_FEATURES].to_dict()


def profile():
    return dict(features=features(), grade="Grade 1", needs=[dict(assessment_id="assessment-1", domain="Number Recognition", score_percentage=40)],
                demonstrated_skills=["Number sequence knowledge"], completed_lesson_ids=[], limit=3)


def test_saved_pipeline_and_all_three_actual_classes():
    assert list(service.model.feature_names_in_) == list(pd.read_csv(ROOT / "data/processed/student_features.csv").columns[1:])
    assert len(service.MODEL_FEATURES) == 28
    assert set(service.model.classes_) == {"Successful", "Needs Practice", "Waiting"}
    assert service.model.named_steps["preprocessor"].named_transformers_["categorical"].handle_unknown == "ignore"
    # These exact rows already exist in the feature-engineering output.
    for student, expected in [("ST001", "Successful"), ("ST006", "Needs Practice"), ("ST004", "Waiting")]:
        result = predict(service.model, features(student))
        assert result["Predicted_Outcome"] == expected
        assert result["Model_Outcome_Compatibility"] == {"Successful": "Successful", "Needs Practice": "Needs Practice", "Waiting": "INSUFFICIENT_DATA"}[expected]
        assert sum(result["Prediction_Probabilities"].values()) == pytest.approx(1)
        assert set(result["Prediction_Probabilities"]) == set(service.model.classes_)
        assert list(validate_features(service.model, features(student)).columns) == service.MODEL_FEATURES


@pytest.mark.parametrize("change", [
    lambda x: x.pop("Average_Score"), lambda x: x.update(Extra=1),
    lambda x: x.update(Average_Score="80"), lambda x: x.update(Average_Score=float("nan")),
    lambda x: x.update(Learning_Style="invented"), lambda x: x.update(Participation_Score=0),
    lambda x: x.update(Total_Activities=0), lambda x: x.update(Historical_Lessons=0),
    lambda x: x.update(Completion_Rate=101), lambda x: x.update(Completed_Historical_Lessons=999),
])
def test_invalid_features_rejected(change):
    values = features(); change(values)
    with pytest.raises(ProfileError): validate_features(service.model, values)


def test_live_endpoint_uses_current_profile_not_dataset_lookup():
    result = client.post("/api/pathways", json=profile())
    assert result.status_code == 200, result.text
    payload = result.json()
    assert payload["Predicted_Outcome"] == "Successful"
    assert payload["Learning_Path"][0]["Recommended_Lesson_ID"] == "LES021"
    assert payload["Learning_Path"][0]["Evidence_ID"] == "assessment-1"
    assert payload["Model_Version"] == hashlib.sha256(service.MODEL_FILE.read_bytes()).hexdigest()


def test_verified_score_outcome_overrides_raw_class_and_success_never_generates_remedial_path():
    p = profile(); p["features"] = features("ST006")  # raw Needs Practice remains visible
    p.update(application_outcome="Successful", verified_performance_percentage=80.0)
    result = client.post("/api/pathways", json=p)
    assert result.status_code == 200, result.text
    payload = result.json()
    assert payload["Predicted_Outcome"] == "Needs Practice"
    assert payload["Application_Outcome"] == "Successful"
    assert payload["Verified_Performance_Percentage"] == 80.0
    assert payload["Learning_Path"] == []
    assert payload["Path_Type"] == "NONE"


def test_below_threshold_uses_only_weak_areas_even_when_raw_model_says_successful():
    p = profile(); p["features"] = features("ST001")  # raw Successful remains unchanged
    p["needs"] = [
        dict(assessment_id="weak", domain="Number Recognition", score_percentage=40),
        dict(assessment_id="strong", domain="Phonics", score_percentage=95),
    ]
    p.update(application_outcome="Needs Practice", verified_performance_percentage=79.99,
             demonstrated_skills=["Number sequence knowledge"])
    result = client.post("/api/pathways", json=p)
    assert result.status_code == 200, result.text
    payload = result.json()
    assert payload["Predicted_Outcome"] == "Successful"
    assert payload["Application_Outcome"] == "Needs Practice"
    assert all(item["Evidence_ID"] == "weak" for item in payload["Learning_Path"])


def test_score_based_needs_practice_with_no_area_below_80_does_not_invent_a_target():
    p = profile(); p["needs"] = [dict(assessment_id="strong", domain="Number Recognition", score_percentage=80)]
    p.update(application_outcome="Needs Practice", verified_performance_percentage=79.0)
    payload = client.post("/api/pathways", json=p).json()
    assert payload["Learning_Path"] == []
    assert payload["Unmatched_Needs"] == []
    assert "No individual learning area is below 80%" in payload["Status"]


def test_missing_evidence_missing_features_and_extra_fields_are_422():
    for mutation in [lambda p: p.update(needs=[]), lambda p: p.update(features={}), lambda p: p.update(ownerId="forged"), lambda p: p.update(limit=0)]:
        p = profile(); mutation(p)
        assert client.post("/api/pathways", json=p).status_code == 422


def test_dataset_endpoint_remains_functional():
    result = client.get("/api/recommendations/ST001")
    assert result.status_code == 200
    assert result.json()["Number_of_Recommendations"] == 2
    assert client.get("/api/recommendations/unknown").status_code == 404


def test_real_catalog_requires_confirmed_prerequisite_and_handles_no_match():
    p = profile(); p["demonstrated_skills"] = []
    result = client.post("/api/pathways", json=p).json()
    assert result["Learning_Path"] == []
    assert result["Status"].startswith("No pathway saved.")
    assert result["Unmatched_Needs"][0]["Reason"] == "PREREQUISITE_NOT_CONFIRMED"
    p = profile(); p["grade"] = "Grade 9"
    wrong_grade = client.post("/api/pathways", json=p).json()
    assert wrong_grade["Learning_Path"] == []
    assert wrong_grade["Unmatched_Needs"][0]["Reason"] == "GRADE_MISMATCH"


@pytest.mark.parametrize("area,reason,required_text", [
    ("Identifying the Fruits names", "MISSING_CURRICULUM_MAPPING", "Plants lesson"),
    ("Identifying the Birds names", "MISSING_CURRICULUM_MAPPING", "no bird-identification"),
    ("Songs", "MISSING_CURRICULUM_MAPPING", "no music"),
])
def test_unmapped_worksheet_areas_explain_exact_curriculum_gap(area, reason, required_text):
    p = profile(); p["needs"] = [dict(assessment_id="worksheet:actual-1", domain=area, score_percentage=40)]
    response = client.post("/api/pathways", json=p)
    assert response.status_code == 200
    result = response.json()
    assert result["Learning_Path"] == []
    assert result["Unmatched_Needs"][0]["Reason"] == reason
    assert required_text.lower() in result["Unmatched_Needs"][0]["Explanation"].lower()
    assert "worksheet:actual-1" == result["Unmatched_Needs"][0]["Evidence_ID"]
    assert required_text.lower() in result["Status"].lower()


def test_unsupported_or_unvalidated_lesson_ids_never_enter_pathway():
    p = profile(); p["needs"] = [dict(assessment_id="a", domain="Number Recognition", score_percentage=40)]
    unsupported = pd.DataFrame([dict(Lesson_ID="NOT-APPROVED", Grade="Grade 1", Subject="Mathematics", Topic="Number Recognition", Competency="Number Sense", Learning_Objective="Identify numbers 11-50 correctly", Difficulty_Level="Basic", Required_Skills="Number sequence knowledge", Classroom_Activities="Number chart activities")])
    result = select_pathway(unsupported, service.validated_learning_paths, p, {"Predicted_Outcome": "Needs Practice"})
    assert result["Learning_Path"] == []
    assert result["Unmatched_Needs"][0]["Reason"] == "ACTIVITY_NOT_VALIDATED"


def test_recommendation_explanation_uses_measured_need_and_verified_curriculum_content():
    p = profile(); p.update(demonstrated_skills=["Number sequence knowledge"], needs=[dict(assessment_id="worksheet:score-1", domain="Number Recognition", score_percentage=35)])
    result = select_pathway(pd.read_csv(ROOT / "data/processed/curriculum_clean.csv"), service.validated_learning_paths, p, {"Predicted_Outcome": "Needs Practice"})
    assert result["Learning_Path"][0]["Recommended_Lesson_ID"] == "LES021"
    assert result["Learning_Path"][0]["Evidence_ID"] == "worksheet:score-1"
    assert "Number Recognition evidence is 35%" in result["Learning_Path"][0]["Selection_Reason"]


def catalog():
    # Same fields as the actual curriculum CSV, with explicit fixture semantics.
    return pd.DataFrame([
        dict(Lesson_ID="L1", Grade="Grade 1", Subject="English", Topic="Phonics", Competency="Reading", Learning_Objective="Read sounds", Difficulty_Level="Beginner", Required_Skills="Sound awareness", Classroom_Activities="Sound cards"),
        dict(Lesson_ID="L2", Grade="Grade 1", Subject="Math", Topic="Counting", Competency="Numbers", Learning_Objective="Count objects", Difficulty_Level="Basic", Required_Skills="Counting readiness", Classroom_Activities="Count blocks"),
        dict(Lesson_ID="L3", Grade="Grade 1", Subject="Math", Topic="Counting", Competency="Numbers", Learning_Objective="Count groups", Difficulty_Level="Intermediate", Required_Skills="Counting readiness", Classroom_Activities="Count groups"),
    ])


def test_ranking_uses_need_scores_difficulty_completed_and_validated_references():
    p = profile(); p.update(demonstrated_skills=["Sound awareness", "Counting readiness"], needs=[
        dict(assessment_id="a", domain="Counting", score_percentage=20),
        dict(assessment_id="b", domain="Phonics", score_percentage=50)])
    allowed = pd.DataFrame({"Recommended_Lesson_ID": ["L1", "L2", "L3", "missing"]})
    result = select_pathway(catalog(), allowed, p, {"Predicted_Outcome": "Successful"})
    assert [x["Recommended_Lesson_ID"] for x in result["Learning_Path"]] == ["L2", "L3", "L1"]
    result = select_pathway(catalog(), allowed, p, {"Predicted_Outcome": "Waiting"})
    assert [x["Recommended_Lesson_ID"] for x in result["Learning_Path"]] == ["L1"]
    p["completed_lesson_ids"] = ["L2"]
    result = select_pathway(catalog(), allowed, p, {"Predicted_Outcome": "Needs Practice"})
    assert [x["Recommended_Lesson_ID"] for x in result["Learning_Path"]] == ["L1"]
    # New evidence changes ranking without changing model features.
    p["completed_lesson_ids"] = []; p["needs"][0]["score_percentage"] = 90
    assert select_pathway(catalog(), allowed, p, {"Predicted_Outcome": "Successful"})["Learning_Path"][0]["Recommended_Lesson_ID"] == "L1"


def test_invalid_missing_and_duplicate_catalog_entries_are_not_selected():
    p = profile(); p.update(demonstrated_skills=["Sound awareness"], needs=[dict(assessment_id="a", domain="Phonics", score_percentage=30)])
    allowed = pd.DataFrame({"Recommended_Lesson_ID": ["L1"]})
    assert select_pathway(catalog(), pd.DataFrame({"Recommended_Lesson_ID": ["unknown"]}), p, {"Predicted_Outcome": "Successful"})["Learning_Path"] == []
    duplicated = pd.concat([catalog(), catalog().iloc[:1]])
    assert select_pathway(duplicated, allowed, p, {"Predicted_Outcome": "Successful"})["Learning_Path"] == []
    with pytest.raises(ProfileError): select_pathway(pd.DataFrame(), allowed, p, {"Predicted_Outcome": "Successful"})


def test_notebook_feature_formulas_reproduce_real_fixture_rows():
    from features import build_features
    data = ROOT / "data/processed"
    tables = {name: pd.read_csv(data / f"{name}_clean.csv") for name in ["activity", "assessment", "teacher", "historical"]}
    for student in ["ST001", "ST006", "ST004"]:
        rows = {name: table[table.Student_ID == student] for name, table in tables.items()}
        expected = features(student)
        # Select the documented observation matching this saved feature snapshot.
        teachers = rows["teacher"]
        for key in ["Participation", "Attention_Level", "Confidence", "Collaboration", "Learning_Difficulty", "Learning_Style"]:
            teachers = teachers[teachers[key] == expected[key]]
        assert not teachers.empty
        actual = build_features(rows["activity"].to_dict("records"), rows["assessment"].to_dict("records"), teachers.iloc[0].to_dict(), rows["historical"].to_dict("records"))
        for key, value in expected.items():
            if isinstance(value, str): assert actual[key] == value
            else: assert actual[key] == pytest.approx(value)
        assert predict(service.model, actual)["Predicted_Outcome"] == predict(service.model, expected)["Predicted_Outcome"]
    with pytest.raises(ProfileError): build_features([], [], {}, [])


@pytest.mark.parametrize("student,expected", [("ST001", "Successful"), ("ST006", "Needs Practice"), ("ST004", "Waiting")])
def test_live_api_all_classes_with_unchanged_real_features(student, expected):
    p = profile(); p["features"] = features(student)
    response = client.post("/api/pathways", json=p)
    assert response.status_code == 200
    assert response.json()["Predicted_Outcome"] == expected


def test_raw_record_endpoint_builds_features_and_rejects_insufficient_data():
    data = ROOT / "data/processed"
    tables = {name: pd.read_csv(data / f"{name}_clean.csv").query('Student_ID == "ST001"') for name in ["activity", "assessment", "teacher", "historical"]}
    observation = tables["teacher"]
    for key in ["Participation", "Attention_Level", "Confidence", "Collaboration", "Learning_Difficulty", "Learning_Style"]:
        observation = observation[observation[key] == features()[key]]
    p = dict(activities=tables["activity"].to_dict("records"), assessments=tables["assessment"].to_dict("records"), observation=observation.iloc[0].to_dict(), historical_lessons=tables["historical"].to_dict("records"))
    p.update({key: value for key, value in profile().items() if key != "features"})
    response = client.post("/api/pathways/from-records", json=p)
    assert response.status_code == 200, response.text
    assert response.json()["Predicted_Outcome"] == "Successful"
    assert response.json()["Learning_Path"][0]["Recommended_Lesson_ID"] == "LES021"
    p["observation"] = {}
    assert client.post("/api/pathways/from-records", json=p).status_code == 422


def test_exact_requested_feature_schema_and_invalid_source_categories():
    expected = "Average_Score Average_Attempts Average_Time_Taken Total_Activities Completion_Rate Pre_Test Post_Test Unit_Test Monthly_Test Term_Result Motor_Skills_Score Creativity_Score Interactive_Engagement Test_Improvement Participation Attention_Level Confidence Collaboration Learning_Difficulty Learning_Style Participation_Score Attention_Score Confidence_Score Collaboration_Score Historical_Lessons Average_Sequence_No Completed_Historical_Lessons Historical_Completion_Rate".split()
    assert service.MODEL_FEATURES == expected
    from features import build_features
    data = ROOT / "data/processed"
    a = pd.read_csv(data / "activity_clean.csv").query('Student_ID == "ST001"').to_dict("records")
    e = pd.read_csv(data / "assessment_clean.csv").query('Student_ID == "ST001"').to_dict("records")
    h = pd.read_csv(data / "historical_clean.csv").query('Student_ID == "ST001"').to_dict("records")
    observation = {key: features()[key] for key in ["Participation", "Attention_Level", "Confidence", "Collaboration", "Learning_Difficulty", "Learning_Style"]}
    a[0]["Completion_Status"] = "UNKNOWN"
    with pytest.raises(ProfileError): build_features(a, e, observation, h)


def test_backend_inference_token_when_configured(monkeypatch):
    monkeypatch.setattr(service, "INFERENCE_TOKEN", "isolated-test-token")
    assert client.post("/api/pathways", json=profile()).status_code == 401
    assert client.post("/api/pathways", json=profile(), headers={"Authorization": "Bearer isolated-test-token"}).status_code == 200


def test_recorded_feedback_and_difficulty_rank_existing_eligible_curriculum():
    # Isolated catalog fixture; it never changes the saved training data or model.
    catalog = pd.DataFrame([
        dict(Lesson_ID="TEST-A", Grade="Grade 1", Subject="Math", Topic="Number Recognition", Competency="Recognize numbers", Learning_Objective="Count numbers", Difficulty_Level="Beginner", Required_Skills="", Classroom_Activities="Number cards"),
        dict(Lesson_ID="TEST-B", Grade="Grade 1", Subject="Math", Topic="Number Recognition Pencil control", Competency="Recognize numbers", Learning_Objective="Number tracing and pencil control", Difficulty_Level="Beginner", Required_Skills="", Classroom_Activities="Trace numbers"),
    ])
    validated = pd.DataFrame({"Recommended_Lesson_ID": ["TEST-A", "TEST-B"]})
    base = profile()
    before = dict(base["features"])
    base.update(recorded_difficulty="Pencil control", measured_improvement=-5,
                teacher_context=[dict(evidence_id="feedback:fixture", next_step="Practise pencil control", improvement="Number tracing needs support")])
    result = select_pathway(catalog, validated, base, predict(service.model, base["features"]))
    assert result["Learning_Path"][0]["Recommended_Lesson_ID"] == "TEST-B"
    assert "feedback:fixture" in result["Learning_Path"][0]["Selection_Reason"]
    assert base["features"] == before
    base["completed_lesson_ids"] = ["TEST-B"]
    assert [r["Recommended_Lesson_ID"] for r in select_pathway(catalog, validated, base, predict(service.model, base["features"]))["Learning_Path"]] == ["TEST-A"]
    base["completed_lesson_ids"].append("TEST-A")
    assert select_pathway(catalog, validated, base, predict(service.model, base["features"]))["Learning_Path"] == []
