"""Validate approved teacher labels and timestamped live feature snapshots.

This preparation step never creates labels and never trains or promotes a model.
It accepts the teacher-only verified-outcomes JSON export from SmartPlay. New
exports can include the exact live feature snapshot captured when a label was
confirmed; older exports may be joined to an explicit CSV and timestamp map.
"""
from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import joblib
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
MODEL_PATH = ROOT / "models" / "random_forest.joblib"
FEATURES_PATH = ROOT / "data" / "processed" / "student_features.csv"
MIN_PER_CLASS = 50  # Holdout should contain about 10 students from each class.
ALLOWED_LABELS = {"Needs Practice", "Successful"}


def parse_current_labels(payload: object) -> tuple[dict[str, dict], list[str]]:
    if not isinstance(payload, list):
        raise ValueError("Label export must be a JSON array.")
    current: dict[str, dict] = {}
    issues: list[str] = []
    counts = Counter()
    for row in payload:
        if not isinstance(row, dict):
            issues.append("Ignored a malformed label record.")
            continue
        student_id = str(row.get("studentId", "")).strip()
        revisions = row.get("revisions") or []
        if not student_id or not revisions or not isinstance(revisions[-1], dict):
            issues.append(f"Ignored a label without a current revision (student {student_id or 'unknown'}).")
            continue
        label = revisions[-1]
        outcome = label.get("outcome")
        if label.get("reviewStatus") != "APPROVED" or outcome not in ALLOWED_LABELS:
            issues.append(f"Ignored an unresolved, superseded, or unsupported label for {student_id}.")
            continue
        counts[student_id] += 1
        current[student_id] = {"outcome": outcome, "policyVersion": row.get("policyVersion"), "grade": row.get("studentGrade"),
                               "verifiedAt": label.get("verifiedAt"), "assessmentEvidence": label.get("assessmentEvidence") or [],
                               "featureSnapshot": label.get("featureSnapshot"),
                               "featureSnapshotGeneratedAt": label.get("featureSnapshotGeneratedAt"),
                               "featureSnapshotSourceRecordIds": label.get("featureSnapshotSourceRecordIds") or [],
                               "featureSnapshotStatus": label.get("featureSnapshotStatus"),
                               "featureSnapshotIssue": label.get("featureSnapshotIssue")}
    duplicated = {student_id for student_id, count in counts.items() if count > 1}
    for student_id in duplicated:
        current.pop(student_id, None)
        issues.append(f"Excluded {student_id}: more than one active approved label was exported.")
    return current, issues


def _instant(value: object) -> datetime | None:
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)
    except ValueError:
        return None


def features_from_label_snapshots(labels: dict[str, dict], feature_order: list[str]) -> tuple[pd.DataFrame, dict[str, str], list[dict], list[dict]]:
    """Build the exact feature table from snapshots frozen into label revisions."""
    rows = []
    timestamps = {}
    missing = []
    lineage = []
    for student_id, label in labels.items():
        snapshot = label.get("featureSnapshot")
        generated_at = label.get("featureSnapshotGeneratedAt")
        if not isinstance(snapshot, dict):
            missing.append({"studentId": student_id, "status": label.get("featureSnapshotStatus") or "MISSING",
                            "reason": label.get("featureSnapshotIssue") or "No feature snapshot was captured with this label revision."})
            continue
        missing_features = [name for name in feature_order if name not in snapshot]
        extra_features = sorted(set(snapshot) - set(feature_order))
        if missing_features or extra_features:
            missing.append({"studentId": student_id, "status": "SCHEMA_MISMATCH",
                            "reason": "Snapshot feature names do not match the live model contract.",
                            "missingFeatures": missing_features, "extraFeatures": extra_features})
            continue
        rows.append({"Student_ID": student_id, **{name: snapshot[name] for name in feature_order}})
        if isinstance(generated_at, str):
            timestamps[student_id] = generated_at
        lineage.append({"studentId": student_id, "featureSnapshotGeneratedAt": generated_at,
                        "featureSnapshotSourceRecordIds": label.get("featureSnapshotSourceRecordIds", [])})
    return pd.DataFrame(rows, columns=["Student_ID", *feature_order]), timestamps, missing, lineage


def validate(features: pd.DataFrame, labels: dict[str, dict], feature_order: list[str],
             feature_timestamps: dict[str, str] | None = None,
             numeric_features: set[str] | None = None) -> tuple[pd.DataFrame, dict]:
    numeric_features = numeric_features or set()
    report = {"featureOrderMatchesLiveModel": list(features.columns[1:]) == feature_order,
              "featureColumnsExpected": feature_order, "featureColumnsFound": list(features.columns[1:]),
              "featureRows": len(features), "duplicateFeatureIds": [], "missingFeatureRows": [],
              "unmatchedLabels": [], "invalidFeatureRows": [], "duplicateFeatureVectors": 0,
              "unverifiedFeatureTimestamps": [], "postLabelFeatureSnapshots": [],
              "constantFeatures": [], "potentialThresholdSeparators": [], "potentialTargetLeakage": [], "targetLeakageReview":
              ["The teacher suggestion is calculated from assessment percentages. Compare assessment evidence IDs with snapshot source IDs and review whether assessment-derived features encode the target policy."],
              "timestamp": datetime.now(timezone.utc).isoformat()}
    if "Student_ID" not in features.columns or list(features.columns[1:]) != feature_order:
        report["schemaError"] = "Feature table must contain Student_ID followed by the exact live feature order."
        return pd.DataFrame(), report
    normalized_ids = features.Student_ID.astype(str).str.strip()
    duplicated_ids = set(normalized_ids[normalized_ids.duplicated(keep=False)])
    report["duplicateFeatureIds"] = sorted(duplicated_ids)
    feature_ids = set(normalized_ids)
    report["unmatchedLabels"] = sorted(set(labels) - feature_ids)
    report["missingFeatureRows"] = sorted(set(labels) - feature_ids)
    report["matchedStudentIds"] = sorted(set(labels) & feature_ids)
    report["unmatchedFeatureRows"] = sorted(feature_ids - set(labels))
    aligned = features.copy()
    aligned["Student_ID"] = aligned.Student_ID.astype(str).str.strip()
    by_id = aligned.set_index("Student_ID", drop=False)
    accepted = []
    for student_id, label in labels.items():
        if student_id not in by_id.index or student_id in duplicated_ids:
            if student_id in duplicated_ids:
                report["invalidFeatureRows"].append({"studentId": student_id, "reason": "duplicate feature ID"})
            continue
        row = by_id.loc[student_id, feature_order]
        invalid = []
        normalized_values = {}
        for name, value in row.items():
            if pd.isna(value) or (isinstance(value, str) and not value.strip()):
                invalid.append(name)
            elif name in numeric_features:
                try:
                    numeric = float(value)
                    if not math.isfinite(numeric):
                        invalid.append(name)
                    else:
                        normalized_values[name] = numeric
                except (TypeError, ValueError):
                    invalid.append(name)
            else:
                normalized_values[name] = value
        if invalid:
            report["invalidFeatureRows"].append({"studentId": student_id, "reason": "missing/non-finite features", "features": invalid})
            report["missingFeatureRows"].append(student_id)
            continue
        feature_at = _instant((feature_timestamps or {}).get(student_id))
        label_at = _instant(label.get("verifiedAt"))
        if feature_at is None or label_at is None:
            report["unverifiedFeatureTimestamps"].append(student_id)
            continue
        if feature_at > label_at:
            report["postLabelFeatureSnapshots"].append(student_id)
            continue
        evidence_ids = {str(item.get("assessmentResultId")) for item in label.get("assessmentEvidence", [])
                        if isinstance(item, dict) and item.get("assessmentResultId")}
        source_ids = {str(source).removeprefix("assessment:") for source in label.get("featureSnapshotSourceRecordIds", [])}
        overlapping = sorted(evidence_ids & source_ids)
        if overlapping:
            report["potentialTargetLeakage"].append({"studentId": student_id, "overlappingAssessmentIds": overlapping})
        accepted.append({"Student_ID": student_id, **normalized_values, "Target_Outcome": label["outcome"]})
    prepared = pd.DataFrame(accepted, columns=["Student_ID", *feature_order, "Target_Outcome"])
    report["usableStudentCount"] = len(prepared)
    report["classCounts"] = prepared.Target_Outcome.value_counts().to_dict() if not prepared.empty else {}
    report["classImbalanceRatio"] = (max(report["classCounts"].values()) / min(report["classCounts"].values())
                                     if len(report["classCounts"]) == 2 and min(report["classCounts"].values()) else None)
    report["duplicateFeatureVectors"] = int(prepared.duplicated(subset=feature_order, keep=False).sum()) if not prepared.empty else 0
    report["constantFeatures"] = [name for name in feature_order if features[name].nunique(dropna=True) <= 1]
    for name in feature_order:
        if name not in prepared or not pd.api.types.is_numeric_dtype(prepared[name]) or prepared.Target_Outcome.nunique() < 2:
            continue
        values = prepared[name]
        labels_series = prepared.Target_Outcome
        candidates = sorted(values.unique())
        if len(candidates) < 2:
            continue
        if any(all(((value <= boundary) == (target == labels_series.iloc[0])) for value, target in zip(values, labels_series))
               for boundary in candidates[:-1]):
            report["potentialThresholdSeparators"].append(name)
    report["minimumPerClassForTraining"] = MIN_PER_CLASS
    report["approvedLabelClassCounts"] = dict(Counter(label["outcome"] for label in labels.values()))
    report["additionalLabelsRequiredByClass"] = {label: max(0, MIN_PER_CLASS - int(report["classCounts"].get(label, 0))) for label in sorted(ALLOWED_LABELS)}
    report["trainingGatePassed"] = (report["featureOrderMatchesLiveModel"] and not duplicated_ids and
        not report["unverifiedFeatureTimestamps"] and not report["postLabelFeatureSnapshots"] and
        not report["potentialTargetLeakage"] and
        all(int(report["classCounts"].get(label, 0)) >= MIN_PER_CLASS for label in ALLOWED_LABELS) and
        not report["duplicateFeatureVectors"] and not report["potentialThresholdSeparators"])
    if report["potentialThresholdSeparators"]:
        report["trainingGatePassed"] = False
        report["gateReason"] = "Potential direct threshold separation requires leakage review before training."
    elif report["potentialTargetLeakage"]:
        report["trainingGatePassed"] = False
        report["gateReason"] = "Assessment evidence is also present in feature snapshot lineage; resolve target leakage before training."
    elif report["unverifiedFeatureTimestamps"] or report["postLabelFeatureSnapshots"]:
        report["trainingGatePassed"] = False
        report["gateReason"] = "Feature snapshot timestamps are missing or occur after teacher label verification."
    elif not report["trainingGatePassed"]:
        report["gateReason"] = "Insufficient independently labeled student groups or feature data for a stratified student-level holdout."
    return prepared, report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--labels-json", required=True, type=Path, help="Teacher-only verified-outcomes JSON export")
    parser.add_argument("--features", type=Path, help="Optional feature CSV. Omit to use snapshots frozen in each label revision.")
    parser.add_argument("--feature-timestamps-json", type=Path, help="Required with --features: JSON object mapping student IDs to feature snapshot timestamps")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "data" / "candidate" / "binary-v1")
    args = parser.parse_args()
    model = joblib.load(MODEL_PATH)
    feature_order = list(model.feature_names_in_)
    preprocessor = model.named_steps.get("preprocessor") if hasattr(model, "named_steps") else None
    numeric_features = set()
    if preprocessor is not None:
        numeric_features = {feature for name, _transformer, columns in preprocessor.transformers_ if name == "numerical" for feature in columns}
    labels, label_issues = parse_current_labels(json.loads(args.labels_json.read_text()))
    missing_feature_snapshots = []
    if args.features:
        if not args.feature_timestamps_json:
            parser.error("--feature-timestamps-json is required when --features is provided")
        features = pd.read_csv(args.features, dtype={"Student_ID": str})
        timestamps = json.loads(args.feature_timestamps_json.read_text())
    else:
        features, timestamps, missing_feature_snapshots, snapshot_lineage = features_from_label_snapshots(labels, feature_order)
    if args.features:
        snapshot_lineage = []
    prepared, report = validate(features, labels, feature_order, timestamps, numeric_features)
    report["approvedLabelCount"] = len(labels)
    report["labelIssues"] = label_issues
    report["missingFeatureSnapshots"] = missing_feature_snapshots
    report["featureSnapshotLineage"] = snapshot_lineage
    report["trainingGatePassed"] = bool(report.get("trainingGatePassed"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    report["datasetFingerprint"] = __import__("hashlib").sha256(prepared.to_csv(index=False).encode()).hexdigest()
    report["labelPolicyVersions"] = sorted({value.get("policyVersion") for value in labels.values() if value.get("policyVersion")})
    (args.output_dir / "data_quality_report.json").write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    if report["trainingGatePassed"]:
        prepared.to_csv(args.output_dir / "approved_training_data.csv", index=False)
        print(f"Validated {len(prepared)} student labels. Training gate passed; candidate training remains a separate reviewed step.")
        return 0
    print("Training stopped: " + str(report.get("gateReason", "feature schema validation failed")))
    print(json.dumps({"classCounts": report.get("classCounts", {}), "additionalLabelsRequiredByClass": report.get("additionalLabelsRequiredByClass", {}),
                      "unmatchedLabels": report.get("unmatchedLabels", []), "invalidFeatureRows": report.get("invalidFeatureRows", []),
                      "unverifiedFeatureTimestamps": report.get("unverifiedFeatureTimestamps", []),
                      "postLabelFeatureSnapshots": report.get("postLabelFeatureSnapshots", []),
                      "potentialTargetLeakage": report.get("potentialTargetLeakage", []),
                      "missingFeatureRows": report.get("missingFeatureRows", []),
                      "missingFeatureSnapshots": missing_feature_snapshots,
                      "labelIssues": label_issues}, indent=2))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
