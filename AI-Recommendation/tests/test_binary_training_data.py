import pandas as pd
from scripts.prepare_binary_training_data import parse_current_labels, validate, features_from_label_snapshots
from scripts.prepare_binary_training_data import ROOT
import joblib


def label(student, outcome="Successful", status="APPROVED"):
    return {"studentId": student, "studentGrade": "Grade 1", "policyVersion": "assessment-threshold-80-v1",
            "revisions": [{"outcome": outcome, "reviewStatus": status, "verifiedAt": "2026-10-22T00:00:00Z", "assessmentEvidence": []}]}


def test_only_current_approved_binary_labels_are_training_targets():
    labels, issues = parse_current_labels([label("s1"), label("s2", "Waiting"), label("s3", status="SUPERSEDED")])
    assert set(labels) == {"s1"}
    assert len(issues) == 2


def test_duplicate_active_labels_exclude_that_student():
    labels, issues = parse_current_labels([label("s1"), label("s1", "Needs Practice")])
    assert "s1" not in labels
    assert any("more than one active" in issue for issue in issues)


def test_live_feature_order_is_preserved_and_small_data_stops_training():
    model = joblib.load(ROOT / "models" / "random_forest.joblib")
    features = pd.read_csv(ROOT / "data" / "processed" / "student_features.csv", dtype={"Student_ID": str})
    labels = {sid: {"outcome": "Successful" if index < 9 else "Needs Practice", "policyVersion": "test", "grade": "Grade 1", "verifiedAt": "2026-10-22T10:00:00Z", "assessmentEvidence": []}
              for index, sid in enumerate(features.Student_ID.astype(str))}
    timestamps = {sid: "2026-10-22T09:00:00Z" for sid in labels}
    prepared, report = validate(features, labels, list(model.feature_names_in_), timestamps)
    assert list(prepared.columns[1:-1]) == list(model.feature_names_in_)
    assert len(model.feature_names_in_) == 28
    assert not report["trainingGatePassed"]
    assert report["classCounts"] == {"Successful": 9, "Needs Practice": 6}
    assert report["additionalLabelsRequiredByClass"] == {"Needs Practice": 44, "Successful": 41}


def test_feature_snapshot_must_precede_label_verification():
    model = joblib.load(ROOT / "models" / "random_forest.joblib")
    features = pd.read_csv(ROOT / "data" / "processed" / "student_features.csv", dtype={"Student_ID": str}).head(2)
    labels = {"ST001": {"outcome": "Successful", "policyVersion": "test", "grade": "Grade 1", "verifiedAt": "2026-10-22T10:00:00Z", "assessmentEvidence": []},
              "ST002": {"outcome": "Needs Practice", "policyVersion": "test", "grade": "Grade 1", "verifiedAt": "2026-10-22T10:00:00Z", "assessmentEvidence": []}}
    timestamps = {"ST001": "2026-10-22T09:00:00Z", "ST002": "2026-10-22T11:00:00Z"}
    prepared, report = validate(features, labels, list(model.feature_names_in_), timestamps)
    assert prepared.Student_ID.tolist() == ["ST001"]
    assert report["postLabelFeatureSnapshots"] == ["ST002"]
    assert not report["trainingGatePassed"]


def test_join_reports_unmatched_and_duplicate_feature_ids():
    model = joblib.load(ROOT / "models" / "random_forest.joblib")
    features = pd.read_csv(ROOT / "data" / "processed" / "student_features.csv", dtype={"Student_ID": str})
    features.loc[len(features)] = features.iloc[0]
    labels = {"ST001": {"outcome": "Successful", "policyVersion": "test", "grade": "Grade 1", "verifiedAt": None, "assessmentEvidence": []},
              "mongo-id": {"outcome": "Needs Practice", "policyVersion": "test", "grade": "Grade 1", "verifiedAt": None, "assessmentEvidence": []}}
    _, report = validate(features, labels, list(model.feature_names_in_))
    assert "ST001" in report["duplicateFeatureIds"]
    assert report["unmatchedLabels"] == ["mongo-id"]


def test_export_snapshots_build_exact_live_schema_and_timestamped_join():
    model = joblib.load(ROOT / "models" / "random_forest.joblib")
    feature_order = list(model.feature_names_in_)
    snapshot = {name: ("Medium" if name in {"Participation", "Attention_Level", "Confidence", "Collaboration", "Learning_Difficulty", "Learning_Style"} else 1)
                for name in reversed(feature_order)}
    labels = {"live-student": {"outcome": "Successful", "verifiedAt": "2026-10-22T10:00:00Z",
                               "featureSnapshot": snapshot, "featureSnapshotGeneratedAt": "2026-10-22T09:00:00Z"}}
    features, timestamps, missing, lineage = features_from_label_snapshots(labels, feature_order)
    prepared, report = validate(features, labels, feature_order, timestamps)
    assert list(features.columns) == ["Student_ID", *feature_order]
    assert prepared.Student_ID.tolist() == ["live-student"]
    assert report["matchedStudentIds"] == ["live-student"]
    assert not missing
    assert lineage[0]["studentId"] == "live-student"


def test_snapshot_schema_mismatch_and_missing_snapshot_are_reported_not_filled():
    model = joblib.load(ROOT / "models" / "random_forest.joblib")
    feature_order = list(model.feature_names_in_)
    labels = {
        "missing": {"outcome": "Needs Practice", "verifiedAt": "2026-10-22T10:00:00Z", "featureSnapshotStatus": "INCOMPLETE", "featureSnapshotIssue": "Missing observations"},
        "wrong-schema": {"outcome": "Successful", "verifiedAt": "2026-10-22T10:00:00Z", "featureSnapshot": {"wrong": 1}, "featureSnapshotGeneratedAt": "2026-10-22T09:00:00Z"},
    }
    features, timestamps, missing, _lineage = features_from_label_snapshots(labels, feature_order)
    prepared, report = validate(features, labels, feature_order, timestamps)
    assert prepared.empty
    assert {item["studentId"] for item in missing} == {"missing", "wrong-schema"}
    assert report["missingFeatureRows"] == ["missing", "wrong-schema"]
    assert not report["trainingGatePassed"]


def test_assessment_sources_shared_with_label_evidence_are_flagged_as_leakage():
    model = joblib.load(ROOT / "models" / "random_forest.joblib")
    feature_order = list(model.feature_names_in_)
    snapshot = {name: ("Medium" if name in {"Participation", "Attention_Level", "Confidence", "Collaboration", "Learning_Difficulty", "Learning_Style"} else 1)
                for name in feature_order}
    labels = {"s1": {"outcome": "Successful", "verifiedAt": "2026-10-22T10:00:00Z",
                      "assessmentEvidence": [{"assessmentResultId": "a1"}],
                      "featureSnapshot": snapshot, "featureSnapshotGeneratedAt": "2026-10-22T09:00:00Z",
                      "featureSnapshotSourceRecordIds": ["assessment:a1"]}}
    features, timestamps, _missing, _lineage = features_from_label_snapshots(labels, feature_order)
    _prepared, report = validate(features, labels, feature_order, timestamps)
    assert report["potentialTargetLeakage"] == [{"studentId": "s1", "overlappingAssessmentIds": ["a1"]}]
    assert not report["trainingGatePassed"]
