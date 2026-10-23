# Teacher-verified student outcome labeling policy

## Policy

Version: `assessment-threshold-80-v1`.

Only current, authorized, completed assessment records with finite scores, positive maximum scores, and `0 <= score <= maximum` contribute to the displayed average. A zero score is valid; incomplete, malformed, unauthorized worksheet, and missing results are excluded rather than counted as zero. The suggestion is the arithmetic mean of valid assessment percentages: at least 80% suggests `Successful`; below 80% suggests `Needs Practice`. No valid completed assessments means no label can be approved.

The suggestion is a teacher review aid, not an automatic label. The authenticated teacher must explicitly confirm one of the two allowed outcomes. If the choice differs from the suggestion, a rationale is required. Student ownership is checked through the existing student service, worksheet records must belong to the authenticated teacher, and archived students cannot receive new labels.

## Audit and corrections

The collection `verified_student_outcomes` stores one document per student and policy version. Its current revision records the approved label, computed suggestion, assessment count/average, source assessment IDs and values, teacher account ID, verification timestamp, policy version, status, and rationale. Confirming the same outcome against unchanged evidence is idempotent. A correction or confirmation against changed evidence appends a revision and marks the previous revision `SUPERSEDED`; only the latest `APPROVED` revision is returned by the teacher training export.

## Training-data preparation gate

`AI-Recommendation/scripts/prepare_binary_training_data.py` joins the current approved teacher-label export to the existing feature table by stable student ID and checks the live model's exact 28-column order. It reports unmatched IDs, duplicate labels/feature IDs, missing or non-finite values, duplicate feature vectors, constant features, class balance, and numeric features that could perfectly separate the labels at a threshold. Label metadata is not included among model features.

The current conservative gate requires at least 50 distinct approved students in each class (to reserve a useful stratified student-level holdout), no duplicate IDs/vectors, exact feature compatibility, and review of any possible perfect numeric threshold separator. This is a minimum process gate, not a guarantee of generalization. The existing history-derived dataset does not contain independently teacher-verified labels and must not be used as a substitute. No teacher-label export was supplied in the repository, so current database label counts have not been audited; the script calculates the exact shortfall from an export. An empty export requires 50 approved student labels in each class.

The script only prepares data and a quality report; it does not train or promote a model. Training and promotion remain separate review decisions. `random_forest.joblib` and current production inference remain unchanged.

## Implementation and verification status

- Teacher review API: `GET/POST /api/students/{studentId}/verified-outcome`.
- Current approved label export: `GET /api/teacher/verified-outcomes/training-export` (teacher role and owner scoped; returns only each student's current approved revision).
- Student Profile shows the assessment count, valid average, evidence rows, policy suggestion, explicit outcome confirmation, rationale on disagreement, and revision history.
- Python data preparation: `python -m scripts.prepare_binary_training_data --labels-json <teacher-export.json> [--feature-timestamps-json <student-id-to-ISO-time.json>]` from `AI-Recommendation/`. A row cannot pass the gate without a feature snapshot timestamp at or before label verification.
- No teacher-label export is currently present, and the historic `ST001`–`ST015` feature rows are not teacher-verified labels. No candidate was trained.
- Initial verification: Python suite 41 passed; backend Maven suite passed (Mongo persistence tests remain skipped unless their dedicated test URI is configured); frontend production build passed. The direct frontend Node suite then had 77 passing and 8 failing tests in dashboard/feedback expectations. `git diff --check` passed.
- The live model artifact, live student assessments, and live MongoDB data were not modified by this work.

## Continuation verification (2026-10-22)

The Student Profile now keeps **Download approved labels for training review** available while assessment evidence is loading or unavailable. The control calls the teacher-only, owner-scoped export and reports whether it downloaded approved labels or an empty export. This avoids hiding already-approved labels just because the currently selected student's evidence is no longer available. A component regression test covers the loading state.

No actual application export could be retrieved during this verification: this checkout has no `.env`, `MONGODB_URI` or `MONGODB_TEST_URI` environment variable, no repository export/candidate file, no MongoDB listener on the default local port, and no backend listener. Consequently, the live database's approved label counts are unknown; they are not zero by inference. No preparation command was run against a fabricated or historical substitute.

To create the real export, start the project with its normal MongoDB and backend configuration, sign in as the teacher who owns the students, open **Student Profile**, select each student, and use **Review student outcome**. Check the displayed completed assessment rows and average, explicitly confirm `Needs Practice` or `Successful`, and supply a rationale if the choice differs from the 80% suggestion. Corrections create a new revision. Use **Download approved labels for training review** to export the current approved labels for that teacher. The export endpoint is `GET /api/teacher/verified-outcomes/training-export`; it requires the authenticated teacher session and exports only that teacher's current approved labels.

Before the preparation gate can pass, the export must join by stable `studentId` to a feature row with the exact live 28-feature order, and `--feature-timestamps-json` must map each ID to a genuine feature-snapshot time no later than `verifiedAt`. The checked-in historical table uses IDs `ST001`–`ST015`; do not assume it contains the live roster. If those IDs do not match or no trustworthy point-in-time feature table and timestamps exist, the gate must remain closed until a correctly joined live snapshot is provided. The 50-per-class minimum still applies after all exclusions.

Continuation checks: backend Maven suite passed (Mongo persistence cases were skipped because no dedicated test URI is configured); Python recommendation suite passed (41 tests); production build passed; `git diff --check` passed. The complete frontend Node suite reports 79 passing and 8 failing tests; the eight failures are in dashboard and lesson-feedback expectations, while the label-export availability and active-model-status regression tests pass. The live three-class model and student database were not changed.

## Timestamped live feature snapshots (2026-10-22)

When a teacher confirms an outcome, the backend now calls the existing `LiveFeatureService` using the authenticated teacher's owner scope and stores the resulting exact 28-feature map, `generatedAt`, and source record IDs in that approved revision. The snapshot is created before `verifiedAt`, so the export can establish point-in-time ordering. If live features are incomplete (HTTP 422), the teacher's evidence-based label is retained, but the revision says `INCOMPLETE`, includes the missing-data reason, and has no feature row. Reconfirming unchanged evidence is idempotent when a snapshot already exists; if a prior revision had no snapshot and a complete snapshot becomes available, confirmation appends a new auditable revision.

The existing **Download approved labels for training review** button downloads the teacher-owner-scoped JSON export, including only the latest approved revision for each student. New exports carry the feature snapshot inline; no join to historical `ST001`–`ST015` IDs is made. To create a reproducible candidate dataset and quality report, from `AI-Recommendation/` run:

```sh
python -m scripts.prepare_binary_training_data \
  --labels-json /absolute/path/to/smartplay-approved-training-labels.json \
  --output-dir data/candidate/binary-v1
```

This command never trains or promotes a model. It writes `data_quality_report.json`; only if every gate passes does it also write `approved_training_data.csv`. The report includes approved and eligible class counts, exact feature order, matched/unmatched student IDs, missing or invalid feature rows, duplicate IDs/vectors, timestamp violations, feature source lineage, constant features, possible numeric threshold separators, assessment evidence/source overlap, and the 50-per-class shortfall. Shared assessment evidence and feature sources are treated as potential target leakage and close the gate for review. An older export without inline snapshots can only be used with a genuine feature CSV and real timestamp map supplied via `--features` and `--feature-timestamps-json`; do not use the historic feature CSV for live IDs or invent timestamps.

The current execution environment still has no configured MongoDB URI, running backend, authenticated teacher session, or downloaded live export. Therefore actual live approved counts remain **unknown**, not zero; the preparation gate was not run against substitute data, and no candidate was trained. Pipeline tests cover the embedded snapshot path, schema mismatch, and missing snapshots. Existing model and student data remain untouched.

### Local collection and preparation steps

Use the project's intended local setup (not a production database): start local MongoDB with `brew services start mongodb-community`; in `backend/`, run `MONGODB_URI=mongodb://localhost:27017/smartplay mvn spring-boot:run`; in a second terminal, run `cd frontend && npm run dev`. Register or log in with the teacher account that owns the students. In Student Profile, review each student's completed valid assessment rows and confirm the teacher label. A ready snapshot additionally requires the actual live pipeline's activity attempts, completion time and status; each of its eight assessment measures plus a valid paired Pre_Test/Post_Test; a valid teacher observation; and valid historical lesson metadata. The feature service reports missing inputs and does not substitute zeros. Download the teacher-scoped export from the review panel, then run the preparation command above. If using an existing database elsewhere, set `MONGODB_URI` only to the authorized development database; do not copy production credentials into project files or use a production URI for this training preparation.

The feature pipeline includes assessment-derived values while the review suggestion is based on completed assessment percentages. The preparation report compares assessment evidence IDs to feature snapshot source IDs; any overlap marks possible target leakage and closes the training gate for review. A later candidate must only be trained after this relationship is resolved or an independent target/data policy is approved. The code intentionally does not waive this check.

Pipeline verification for this continuation: `python -m pytest -q tests/test_binary_training_data.py tests/test_live_pathways.py` passed (41 tests); focused backend `VerifiedStudentOutcomeServiceTest` passed (10 tests); full backend `mvn test -q` passed (137 tests, 35 Mongo-backed persistence cases skipped); `npm run build` passed; full frontend Node suite reports 79 passed and 8 failed in the existing dashboard/lesson-feedback expectations; `git diff --check` passed. Since there is no `MONGODB_TEST_URI`, those Mongo persistence tests were not exercised. The current counts and model decision remain unchanged: no real export was available, so no gate run on live data and no candidate training.
