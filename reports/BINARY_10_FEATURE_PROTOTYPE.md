# Ten-feature binary Random Forest prototype

**Status: trained for exploration only; separately callable from the teacher Student Profile.** It is not the production predictor and must not guide student decisions. The existing production model at `AI-Recommendation/models/random_forest.joblib` was not modified.

## Training data and target

The prototype uses the repository's existing `student_features.csv` and `historical_clean.csv`. It follows the label rule in training notebook 04 exactly: assign each student the most frequent historical `Outcome` using `value_counts().idxmax()`. That rule resolves ties according to which outcome appears first in that student's row order; six of the 15 students have tied top outcome counts. The resulting dominant-label counts are 9 `Successful`, 5 `Needs Practice`, and 1 `Waiting`. The one `Waiting` student (`ST004`) was excluded. No labels were made up or edited.

The trained set is therefore only **14 students** (9 Successful, 5 Needs Practice). These are historical-derived labels, not teacher-verified final student outcomes. Since the training target is computed from lesson outcomes and three selected features describe lesson history, target leakage and label instability remain serious limitations.

## Features and output

The candidate uses exactly these ten features, in this order:

1. `Average_Attempts`
2. `Total_Activities`
3. `Completion_Rate`
4. `Participation`
5. `Attention_Level`
6. `Collaboration`
7. `Learning_Difficulty`
8. `Historical_Lessons`
9. `Completed_Historical_Lessons`
10. `Historical_Completion_Rate`

The pipeline scales numeric columns and one-hot encodes categories. The saved candidate's classes are exactly `Needs Practice` and `Successful`, in that probability-column order. It predicts a **general student-level outcome**; it does not predict a specific subject because the training rows do not have subject-level targets or subject inputs.

## Exploratory evaluation

Leave-one-student-out cross-validation was run across the 14 eligible student rows. This keeps each student's single aggregate row out of its own training fold, but cannot compensate for the very small dataset or weak target construction.

| Metric | Result |
| --- | ---: |
| Accuracy | 78.57% |
| Macro precision | 0.775 |
| Macro recall | 0.744 |
| Macro F1 | 0.754 |

Confusion matrix with rows/columns ordered `Needs Practice`, `Successful`:

| Actual \\ Predicted | Needs Practice | Successful |
| --- | ---: | ---: |
| Needs Practice | 3 | 2 |
| Successful | 1 | 8 |

The 78.57% result is not a trustworthy estimate of real-world accuracy: it is based on 14 labels, includes an imbalanced class distribution, and evaluates against outcomes derived from the same historical lesson data.

## Artifacts and reproduction

Run from `AI-Recommendation/`:

```sh
python -m scripts.train_binary_prototype
```

This writes:

- `models/candidates/binary-10-feature-prototype/random_forest.joblib`
- `models/candidates/binary-10-feature-prototype/prototype_report.json`
- `models/candidates/binary-10-feature-prototype/leave_one_student_out_predictions.csv`

It does not change the live model or inference path. It is not suitable for student-facing decisions or personalized subject pathways. For a credible model, collect timestamped pre-outcome feature snapshots and independently teacher-confirmed outcome labels at a much larger student count, and resolve the label/source overlap before training or promotion.

## Experimental application integration

The teacher Student Profile's **RANDOM FOREST AI** section includes **Evaluate experimental model**. This calls the separately authenticated backend route `POST /api/recommendations/student/{studentId}/prototype-prediction`, which checks student ownership and generates the ten features from that student's persisted activity, observation, and historical-lesson records. The backend then calls the AI service's `/api/prototype/predict` route. The candidate artifact is loaded separately from the production artifact and startup checks enforce its ten feature names and two output classes.

The **Classroom Activity Records** area also provides a separate **10-feature student template**. It pre-fills the active roster identity columns and has the ten model feature names as Excel columns. Teachers preview and import the completed workbook; validated values are saved with an upload timestamp in `binary_prototype_feature_snapshots`, separate from `classroom_activities`. The prototype uses the latest imported snapshot for that student when one exists, otherwise it derives inputs from the existing saved evidence. Re-uploading identical values is idempotent; changed values create a newer snapshot.

The evaluation is on demand; it is not saved as a milestone or recommendation, and it creates no learning pathway. The response shows the raw two-class prediction, probabilities, artifact hash, timestamp, generated feature values, and source record IDs. Missing or unsupported persisted values return a validation error; they are not filled with invented defaults. Both backend and Python service must use the same `SMARTPLAY_INFERENCE_TOKEN`; configure the backend AI URL with `SMARTPLAY_AI_BASE_URL` (defaults to `http://127.0.0.1:8000`). For local development, copy `backend/.env.example` to `backend/.env`, replace `SMARTPLAY_INFERENCE_TOKEN` with a random secret, then start the backend from the `backend/` directory. When Python starts from `AI-Recommendation/`, it reads that same ignored backend `.env` if the environment variable is not set. An explicitly set environment variable takes precedence in either service. Restart both services after changing the token. The prototype remains an experiment trained on 14 history-labeled students, not teacher-confirmed outcomes.
