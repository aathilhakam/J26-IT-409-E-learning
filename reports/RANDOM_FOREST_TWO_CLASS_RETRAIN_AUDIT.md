# Random Forest two-class retraining audit

**Decision: do not train or promote a candidate from the current dataset.** The current labeled data is too small and its student-level labels are derived from mixed historical lesson outcomes rather than a clearly defined, independently validated student outcome. Creating a two-class artifact from it would produce misleading confidence. The live model was not changed.

## Existing training and inference flow

- Training notebook: `AI-Recommendation/notebooks/04_model_training.ipynb`.
- Evaluation notebook: `AI-Recommendation/notebooks/05_model_evaluation.ipynb`.
- Training feature table: `AI-Recommendation/data/processed/student_features.csv` (15 rows, one per historical student, 28 feature columns plus `Student_ID`).
- Historical outcomes/statuses: `AI-Recommendation/data/processed/historical_clean.csv` (60 lesson rows).
- Live model: `AI-Recommendation/models/random_forest.joblib`.
- Python service/API: `AI-Recommendation/src/recommendation/service.py`, `AI-Recommendation/src/api/main.py`, and `AI-Recommendation/app.py`.
- The loaded live model is a scikit-learn `Pipeline` with `classes_ = ['Needs Practice', 'Successful', 'Waiting']`.
- `AI-Recommendation/tests/test_live_pathways.py` contains assertions for the current three-class model. Existing three-class expectations must not be changed unless a candidate has passed validation and is deliberately promoted.

## Exact input contract

The existing model exposes these 28 features in this order. The preprocessing pipeline detects numeric and object columns, applies `StandardScaler` to numeric features, and `OneHotEncoder(handle_unknown='ignore', sparse_output=False)` to categorical features. The `Pipeline` preserves this preprocessing as part of the serialized model.

1. `Average_Score`
2. `Average_Attempts`
3. `Average_Time_Taken`
4. `Total_Activities`
5. `Completion_Rate`
6. `Pre_Test`
7. `Post_Test`
8. `Unit_Test`
9. `Monthly_Test`
10. `Term_Result`
11. `Motor_Skills_Score`
12. `Creativity_Score`
13. `Interactive_Engagement`
14. `Test_Improvement`
15. `Participation`
16. `Attention_Level`
17. `Confidence`
18. `Collaboration`
19. `Learning_Difficulty`
20. `Learning_Style`
21. `Participation_Score`
22. `Attention_Score`
23. `Confidence_Score`
24. `Collaboration_Score`
25. `Historical_Lessons`
26. `Average_Sequence_No`
27. `Completed_Historical_Lessons`
28. `Historical_Completion_Rate`

The saved model's actual `feature_names_in_` matches this order. No feature or preprocessing change was made.

## Label source and data counts

Notebook cell 4 loads `historical_clean.csv`. Cells 5–9 count each student's `Outcome` values and select the most frequent outcome as `Target_Outcome`. This means the original training target is derived from the lesson-history table, not from a separately recorded final student outcome.

The notebook's assigned student-level label counts are:

| Label | Student rows |
| --- | ---: |
| `Successful` | 9 |
| `Needs Practice` | 5 |
| `Waiting` | 1 |
| **Total** | **15** |

`Improving` is also present in the historical table (16 lesson rows), but it is not a student-level class in the saved model. The 60 historical rows contain 21 `Successful`, 10 `Needs Practice`, 16 `Improving`, and 13 `Waiting` outcomes. All 13 `Waiting` rows have status `Not Started`; the 16 `Improving` rows have status `In Progress`, except for one row marked `Completed`. The single student whose dominant notebook label is `Waiting` is ST004. The other 13 `Waiting` lesson rows still participate in the notebook's dominant-outcome calculation for students assigned one of the other labels.

Excluding the `Waiting`-labeled student leaves 14 notebook-labeled groups (9 Successful, 5 Needs Practice), but this does not make the targets reliable: labels still come from a dominant class across a small set of mixed lesson outcomes, with ties, and the class label is not documented as an independently assessed student-level result.

If considering only completed lesson rows whose outcome is already one of the two requested labels, there are 31 lesson rows (21 Successful, 10 Needs Practice) across 15 student groups. All 15 students have such evidence, but several have a tie or contain both outcomes. A rule for resolving mixed outcomes would itself invent a student-level target policy unless the project owner defines one from the assessment protocol.

## Quality, leakage, and evaluation limits

- `student_features.csv`: 15 rows, 15 unique IDs, no duplicate rows, and no missing cells. Three columns are constant across this small sample: `Total_Activities`, `Historical_Lessons`, and `Average_Sequence_No`.
- `historical_clean.csv`: repeated rows per student. Splitting lesson rows randomly would leak each student's identity-specific feature vector between train and test; evaluation must group by student.
- The notebook currently performs leave-one-out prediction at the student-row level, so it does not split repeated rows across folds. However, its target is still derived from the same students' history, while the feature table includes history-derived completion counts/rates. This is a target-construction and shared-source leakage risk. The available records do not establish that these features are independent of the labels.
- With 5 notebook-assigned minority-class students (or only 5 minority groups after excluding the `Waiting` student), a student-held-out test set and stratified grouped cross-validation would have too few minority examples for stable class-specific metrics. A single split would be highly sensitive to individual students.
- The existing notebook's reported 3-class 80% LOOCV accuracy is not evidence for a two-class model: it evaluates the existing three-class labels, including the status-derived `Waiting` target.

## Safe action taken

This audit only reads the repository datasets and model metadata. No candidate model was trained or saved, no training data was relabeled, the live model and its preprocessing were not overwritten, and no database/student records were accessed or changed. There are no model integration or Student Profile probability changes because doing so without a validated candidate would misrepresent the current three-class live model.

## Follow-up implementation status

The teacher-verified label workflow and safe training-data quality gate have since been added. See `reports/TEACHER_VERIFIED_OUTCOME_LABELING_POLICY.md`. This implementation does not change the findings above: there is still no approved label export available in the repository, so no binary candidate was trained, evaluated, or promoted. The existing production behavior remains three-class until independently labeled data passes the documented gate and a candidate passes review.

## Evidence needed to proceed

Before two-class retraining, provide or approve a documented target policy and obtain more independent, student-level labeled examples for both outcomes. The labels should be based on a defined assessment rule or teacher-verified outcome; `Not Started` and `In Progress` should remain unclassified unless an authorized assessment protocol establishes a valid binary outcome. The dataset needs enough distinct students in each class to reserve a stratified student-level holdout and evaluate class-wise precision/recall/F1 with grouped cross-validation. Once this evidence exists, train a separate versioned candidate, verify its exact classes and probability mapping against this 28-feature contract, and promote only after review. The current live model remains available for rollback.

## Deployment readiness check (2026-10-22)

The deployment request was checked against the current workspace. There is no `AI-Recommendation/data/candidate` artifact and no teacher-label export file. No backend or MongoDB instance is reachable in this environment, so the live `verified_student_outcomes` collection cannot be queried. **Actual live approved counts in either class are unknown.** The repository provides zero export rows to validate; this is not evidence that the live database contains zero labels.

The inspected checked-in feature CSV has 15 rows, 15 unique IDs (`ST001`–`ST015`), and the required 28 columns. It does not contain teacher-approved labels. A valid export must join to matching feature IDs and a timestamped snapshot at or before each label's `verifiedAt`; no such timestamp mapping or approved feature join is present. The preparation command therefore was not run against a substitute, and the 50-per-class gate cannot be assessed or passed.

The live artifact was loaded for inspection and still has classes `Needs Practice`, `Successful`, and `Waiting`, with the exact 28 features above. The actual loading/inference surfaces are `AI-Recommendation/src/recommendation/service.py` and `AI-Recommendation/app.py`; both load `models/random_forest.joblib`. Live-pathway prediction and probability mapping are in `AI-Recommendation/pathways.py`. `backend/.../AIRecommendationClient.java` validates exactly the current three labels and rejects two-class output. The Student Profile model explanation includes a `Waiting` policy entry in `frontend/src/features/recommendations/components/AIRecommendationPanel.jsx`. The training/evaluation notebooks also contain historical `Waiting` labels, but were not rerun or changed. A two-class candidate cannot be safely selected by the current integration until a validated candidate exists and the backend response validation is version-aware.

The Student Profile now identifies the active three-class model and says no validated binary candidate is deployed when the saved probabilities include `Waiting`. It reports a binary class set only when the raw saved probabilities actually contain exactly `Needs Practice` and `Successful`; it does not merge or renormalize old probabilities. This is a status disclosure, not a model promotion.

**Decision:** no candidate trained, evaluated, or promoted. The existing three-class artifact remains the production model; there are no candidate metrics. The artifact file was not changed. Backend, Python, frontend, and build results for the latest workspace check are recorded in `TEACHER_VERIFIED_OUTCOME_LABELING_POLICY.md`; the full frontend suite still has eight existing dashboard/feedback failures. Mongo-backed persistence tests remain skipped without `MONGODB_TEST_URI`.

## Live training-data pipeline update (2026-10-22)

The approval workflow now captures the live `LiveFeatureService` 28-feature snapshot when a teacher confirms a label, with its generation timestamp and source record IDs stored on that specific revision. The approved-label endpoint remains teacher-owner scoped and returns only current approved revisions. The preparation script consumes those embedded snapshots, enforces exact feature names, requires a timestamp no later than `verifiedAt`, and produces a quality report with class counts, joins, missing/invalid rows, duplicates, lineage, leakage indicators, and the 50-student-per-class shortfall. Incomplete snapshots are reported and excluded; no default values are added.

This improves reproducibility but does not supply current evidence: this checkout still has no accessible MongoDB/backend session or exported teacher labels. Actual live class counts are unknown. The preparation gate was not run on historical data or fabricated labels, no candidate was trained, and the live model remains unchanged. See `TEACHER_VERIFIED_OUTCOME_LABELING_POLICY.md` for exact local run/export steps and the preparation command.
