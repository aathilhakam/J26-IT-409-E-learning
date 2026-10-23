# Live Random Forest Feature Readiness Audit

**Snapshot date:** 2026-10-22
**Scope:** 18 active Grade 1 students in the configured local `smartplay` MongoDB database.
**Method:** Read-only queries against MongoDB; feature definitions and calculations traced to `LiveFeatureService.FEATURE_ORDER` and `LiveFeatureService.generate`. No records were changed.

## Findings at a glance

- **0 of 18 students are ready** for the existing 28-feature prediction pipeline. After mapping worksheet evidence into the activity features, each has **9 available features and 19 missing features**; no malformed feature-source values were found in the records used by this audit.
- MongoDB contains **72 worksheet assessment results: 4 distinct worksheet titles × 18 students**. The results have valid scores out of 10, `COMPLETED` status, `attempts: 1`, and numeric `timeTakenMinutes` values (9–45 minutes). Their `measures` maps are empty. The live feature service now uses valid worksheet assessments for the five activity features. The 28-feature order is unchanged; worksheet records are included only when `worksheet: true`.
- The `learning_records` and `teacher_observations` collections contain **0 documents**. The `historical_lessons` collection contains **56 valid records** for the 18 students (3 each for 16 students, 4 each for 2 students).
- The 18 students have four completed worksheet result records each and no separate `learning_records`. Worksheet assessment results now provide the five activity metrics in the live feature calculation.
- Missing means no value can be produced by the existing aggregation. Invalid means source data exists but fails that feature’s validation. **No feature is marked INVALID** in this snapshot. The implementation maps worksheet records into the five activity features and deduplicates against a classroom activity when both carry the same nonblank `activityId`.

## Exact 28-feature schema and live source

| # | Feature | Pipeline source / calculation |
|---:|---|---|
| 1 | `Average_Score` | learning_records plus worksheet assessment_results.score / maxScore; average normalized to 0–100 |
| 2 | `Average_Attempts` | learning_records plus worksheet assessment_results.attempts; arithmetic mean |
| 3 | `Average_Time_Taken` | learning_records plus worksheet assessment_results.timeTakenMinutes; arithmetic mean |
| 4 | `Total_Activities` | count of learning_records plus valid worksheet assessment results |
| 5 | `Completion_Rate` | COMPLETED count / combined activity count × 100; worksheet NOT_COMPLETED is valid and remains incomplete |
| 6 | `Pre_Test` | assessment_results.measures.Pre_Test; mean valid 0–100 values |
| 7 | `Post_Test` | assessment_results.measures.Post_Test; mean valid 0–100 values |
| 8 | `Unit_Test` | assessment_results.measures.Unit_Test; mean valid 0–100 values |
| 9 | `Monthly_Test` | assessment_results.measures.Monthly_Test; mean valid 0–100 values |
| 10 | `Term_Result` | assessment_results.measures.Term_Result; mean valid 0–100 values |
| 11 | `Motor_Skills_Score` | assessment_results.measures.Motor_Skills_Score; mean valid 0–100 values |
| 12 | `Creativity_Score` | assessment_results.measures.Creativity_Score; mean valid 0–100 values |
| 13 | `Interactive_Engagement` | assessment_results.measures.Interactive_Engagement; mean valid 0–100 values |
| 14 | `Test_Improvement` | mean(Post_Test − Pre_Test) for records containing both valid measures |
| 15 | `Participation` | latest teacher_observations.participation category |
| 16 | `Attention_Level` | latest teacher_observations.attentionLevel category |
| 17 | `Confidence` | latest teacher_observations.confidence category |
| 18 | `Collaboration` | latest teacher_observations.collaboration category |
| 19 | `Learning_Difficulty` | latest teacher_observations.learningDifficulty category |
| 20 | `Learning_Style` | latest teacher_observations.learningStyle category |
| 21 | `Participation_Score` | Participation Low=1, Medium=2, High=3 |
| 22 | `Attention_Score` | Attention Level Low=1, Medium=2, High=3 |
| 23 | `Confidence_Score` | Confidence Low=1, Medium=2, High=3 |
| 24 | `Collaboration_Score` | Collaboration Fair=1, Needs improvement=2, Good=3, Excellent=4 |
| 25 | `Historical_Lessons` | historical_lessons document count |
| 26 | `Average_Sequence_No` | mean(historical_lessons.sequenceNumber) |
| 27 | `Completed_Historical_Lessons` | count of historical lessons with completionStatus=Completed |
| 28 | `Historical_Completion_Rate` | Completed historical lessons / history count × 100 |

## Report 1 — Individual student feature-readiness matrix

Each student section lists all 28 features, the feature value/status, source, and why data is missing or invalid. `—` means the current code cannot calculate a value. Percentages are shown to two decimals where needed.

### Kavindu Perera — `6ad3e65e64387f4f2ed2c721`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `70.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `35.50` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `4` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2.5` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `75.00%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 70.00%) included in Average_Score; the remaining 19 features are still missing.

### Tharushi Fernando — `6ad3e65e64387f4f2ed2c722`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `75.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `31.00` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `4` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2.5` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `25.00%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 4 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 75.00%) included in Average_Score; the remaining 19 features are still missing.

### Mohamed Ayaan — `6ad3e65e64387f4f2ed2c723`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `75.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `38.00` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 75.00%) included in Average_Score; the remaining 19 features are still missing.

### Dinuli Jayasinghe — `6ad3e65e64387f4f2ed2c724`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `80.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `35.00` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 80.00%) included in Average_Score; the remaining 19 features are still missing.

### Ashen Wijesinghe — `6ad3e65e64387f4f2ed2c725`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `82.50` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `27.75` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 82.50%) included in Average_Score; the remaining 19 features are still missing.

### Inuki Rathnayake — `6ad3e65e64387f4f2ed2c726`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `72.50` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `34.50` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 72.50%) included in Average_Score; the remaining 19 features are still missing.

### Rizwan Ahmed — `6ad3e65e64387f4f2ed2c727`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `75.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `27.75` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 75.00%) included in Average_Score; the remaining 19 features are still missing.

### Senuli Bandara — `6ad3e65e64387f4f2ed2c728`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `75.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `28.75` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 75.00%) included in Average_Score; the remaining 19 features are still missing.

### Hirusha Kumara — `6ad3e65e64387f4f2ed2c729`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `70.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `33.00` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 70.00%) included in Average_Score; the remaining 19 features are still missing.

### Ayesha Niyas — `6ad3e65e64387f4f2ed2c72a`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `70.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `34.25` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 70.00%) included in Average_Score; the remaining 19 features are still missing.

### Thehan Rodrigo — `6ad3e65e64387f4f2ed2c72b`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `65.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `28.50` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 65.00%) included in Average_Score; the remaining 19 features are still missing.

### Piumi Lakshan — `6ad3e65e64387f4f2ed2c72c`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `72.50` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `33.75` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 72.50%) included in Average_Score; the remaining 19 features are still missing.

### Imran Farook — `6ad3e65e64387f4f2ed2c72d`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `70.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `28.75` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 70.00%) included in Average_Score; the remaining 19 features are still missing.

### Methuli Abeysekara — `6ad3e65e64387f4f2ed2c72e`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `75.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `32.25` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 75.00%) included in Average_Score; the remaining 19 features are still missing.

### Dilan Senanayake — `6ad3e65e64387f4f2ed2c72f`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `67.50` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `31.25` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 67.50%) included in Average_Score; the remaining 19 features are still missing.

### Safa Rameez — `6ad3e65e64387f4f2ed2c730`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `77.50` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `31.75` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 77.50%) included in Average_Score; the remaining 19 features are still missing.

### Bimsara Gunawardena — `6ad3e65e64387f4f2ed2c731`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `85.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `31.75` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 85.00%) included in Average_Score; the remaining 19 features are still missing.

### Oshadi Madushanka — `6ad3e65e64387f4f2ed2c732`

| Feature | Value / status | MongoDB source | Detail |
|---|---|---|---|
| `Average_Score` | `85.00` — AVAILABLE | `learning_records` + `assessment_results` | Four valid completed worksheet scores normalized by maxScore; included in the combined activity average. |
| `Average_Attempts` | `1.00` — AVAILABLE | `learning_records` + `assessment_results` | Four worksheet rows each contain attempts=1; included in the combined activity average. |
| `Average_Time_Taken` | `32.50` — AVAILABLE | `learning_records` + `assessment_results` | Mean of four numeric worksheet timeTakenMinutes values. |
| `Total_Activities` | `4` — AVAILABLE | `assessment_results` | Four valid worksheet results; no separate learning_records rows for this student. |
| `Completion_Rate` | `100.00%` — AVAILABLE | `assessment_results` | All 4 worksheet results have COMPLETED status (4/4 × 100). |
| `Pre_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Post_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Unit_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Monthly_Test` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Term_Result` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Motor_Skills_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Creativity_Score` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Interactive_Engagement` | — MISSING | `assessment_results.measures` | No valid value in assessment_results.measures; all 72 worksheet result documents have an empty measures map. |
| `Test_Improvement` | — MISSING | `assessment_results.measures` | No result contains both valid Pre_Test and Post_Test values in its measures map, so no paired difference can be calculated. |
| `Participation` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Attention_Level` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Confidence` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Collaboration` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Difficulty` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Learning_Style` | — MISSING | `teacher_observations` | No teacher_observations documents exist. |
| `Participation_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported participation category (Low/Medium/High) to a number. |
| `Attention_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported attention category (Low/Medium/High) to a number. |
| `Confidence_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported confidence category (Low/Medium/High) to a number. |
| `Collaboration_Score` | — MISSING | `teacher_observations` | No teacher observation exists to map a supported collaboration category to a number. |
| `Historical_Lessons` | `3` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Average_Sequence_No` | `2` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Completed_Historical_Lessons` | `1` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |
| `Historical_Completion_Rate` | `33.33%` — AVAILABLE | `historical_lessons` | AVAILABLE; all 3 rows have nonblank lessonId, sequence ≥ 1, and a recognized completionStatus. |

**Readiness: NOT READY** — 19 missing features; 9 available features (5 worksheet activity + 4 historical); 0 invalid features. Worksheet-score activity features now use the 4 stored results (normalized average 85.00%) included in Average_Score; the remaining 19 features are still missing.

## Report 2 — Missing-feature summary across all 18 students

| Feature | Missing | Invalid | Available | Note |
|---|---:|---:|---:|---|
| `Average_Score` | 0 | 0 | 18 | Mean of 4 valid worksheet scores normalized to 0–100. |
| `Average_Attempts` | 0 | 0 | 18 | All 72 worksheet records have attempts=1; separate classroom activity records are absent. |
| `Average_Time_Taken` | 0 | 0 | 18 | All worksheet durations are numeric (9–45 minutes); separate classroom activity records are absent. |
| `Total_Activities` | 0 | 0 | 18 | Four valid worksheet records per student; separate learning_records are absent. |
| `Completion_Rate` | 0 | 0 | 18 | All four worksheet records per student are COMPLETED. |
| `Pre_Test` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Post_Test` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Unit_Test` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Monthly_Test` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Term_Result` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Motor_Skills_Score` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Creativity_Score` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Interactive_Engagement` | 18 | 0 | 0 | All worksheet results have an empty measures map; no values in this measure. |
| `Test_Improvement` | 18 | 0 | 0 | No record has a valid Pre_Test and Post_Test pair. |
| `Participation` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Attention_Level` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Confidence` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Collaboration` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Learning_Difficulty` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Learning_Style` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Participation_Score` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Attention_Score` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Confidence_Score` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Collaboration_Score` | 18 | 0 | 0 | No teacher_observations rows exist. |
| `Historical_Lessons` | 0 | 0 | 18 | All 56 historical rows pass the feature service’s lessonId, sequence, and status checks. |
| `Average_Sequence_No` | 0 | 0 | 18 | All 56 historical rows pass the feature service’s lessonId, sequence, and status checks. |
| `Completed_Historical_Lessons` | 0 | 0 | 18 | All 56 historical rows pass the feature service’s lessonId, sequence, and status checks. |
| `Historical_Completion_Rate` | 0 | 0 | 18 | All 56 historical rows pass the feature service’s lessonId, sequence, and status checks. |

**Most-missing features:** 19 features tie at 18/18 students missing: the 8 assessment measures, `Test_Improvement`, and 10 teacher-observation features (six categories plus four derived numeric scores). The 9 available features are the 5 worksheet activity features and 4 historical features.

## Report 1 supplemental — Worksheet activity feature values

| Student | Worksheet results | Stored worksheet titles | `Average_Score` | `Average_Attempts` | `Average_Time_Taken` (min) | `Total_Activities` | `Completion_Rate` |
|---|---:|---|---:|---:|---:|---:|---:|
| Kavindu Perera (`6ad3e65e64387f4f2ed2c721`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 70.00% | 1.00 | 35.50  | 4 | 100.00% |
| Tharushi Fernando (`6ad3e65e64387f4f2ed2c722`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 75.00% | 1.00 | 31.00  | 4 | 100.00% |
| Mohamed Ayaan (`6ad3e65e64387f4f2ed2c723`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 75.00% | 1.00 | 38.00  | 4 | 100.00% |
| Dinuli Jayasinghe (`6ad3e65e64387f4f2ed2c724`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 80.00% | 1.00 | 35.00  | 4 | 100.00% |
| Ashen Wijesinghe (`6ad3e65e64387f4f2ed2c725`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 82.50% | 1.00 | 27.75  | 4 | 100.00% |
| Inuki Rathnayake (`6ad3e65e64387f4f2ed2c726`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 72.50% | 1.00 | 34.50  | 4 | 100.00% |
| Rizwan Ahmed (`6ad3e65e64387f4f2ed2c727`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 75.00% | 1.00 | 27.75  | 4 | 100.00% |
| Senuli Bandara (`6ad3e65e64387f4f2ed2c728`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 75.00% | 1.00 | 28.75  | 4 | 100.00% |
| Hirusha Kumara (`6ad3e65e64387f4f2ed2c729`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 70.00% | 1.00 | 33.00  | 4 | 100.00% |
| Ayesha Niyas (`6ad3e65e64387f4f2ed2c72a`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 70.00% | 1.00 | 34.25  | 4 | 100.00% |
| Thehan Rodrigo (`6ad3e65e64387f4f2ed2c72b`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 65.00% | 1.00 | 28.50  | 4 | 100.00% |
| Piumi Lakshan (`6ad3e65e64387f4f2ed2c72c`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 72.50% | 1.00 | 33.75  | 4 | 100.00% |
| Imran Farook (`6ad3e65e64387f4f2ed2c72d`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 70.00% | 1.00 | 28.75  | 4 | 100.00% |
| Methuli Abeysekara (`6ad3e65e64387f4f2ed2c72e`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 75.00% | 1.00 | 32.25  | 4 | 100.00% |
| Dilan Senanayake (`6ad3e65e64387f4f2ed2c72f`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 67.50% | 1.00 | 31.25  | 4 | 100.00% |
| Safa Rameez (`6ad3e65e64387f4f2ed2c730`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 77.50% | 1.00 | 31.75  | 4 | 100.00% |
| Bimsara Gunawardena (`6ad3e65e64387f4f2ed2c731`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 85.00% | 1.00 | 31.75  | 4 | 100.00% |
| Oshadi Madushanka (`6ad3e65e64387f4f2ed2c732`) | 4 | Song Book — Grade 1, eGr1_2OM Birds.pdf, eGr1_2OM Fruits.pdf, eGr1_2OM Lets Go Out.pdf | 85.00% | 1.00 | 32.50  | 4 | 100.00% |

Database query summary: 72/72 assessment documents are `worksheet: true`, each score is numeric and within `0..maxScore` (maxScore 10), each status is `COMPLETED`, and `measures` is an empty object. Each of the four worksheet titles has 18 records. `attempts` is 1 on all 72 worksheet assessment documents; `timeTakenMinutes` is numeric on all 72 and ranges from 9 to 45 minutes. These values are now consumed by the five activity features. All 18 rows below have four completed worksheet results, so Total_Activities=4 and Completion_Rate=100%.

## Report 3 — Data needed to resolve the gaps

1. **Activity features (5):** the worksheet-derived values are now available to the feature service. Future worksheet results must retain a valid score/maxScore, attempts, timeTakenMinutes, and status. Any separate classroom activities still need to be saved through the classroom activity workflow into `learning_records`.
2. **Assessment measurements (8 features):** record actual values for Pre Test, Post Test, Unit Test, Monthly Test, Term Result, Motor Skills Score, Creativity Score, and Interactive Engagement in the existing `assessment_results.measures` map, each as a finite number from 0 to 100. The 72 worksheet results contain none of these measurement keys.
3. **Test improvement:** record valid Pre_Test and Post_Test values together in the same assessment result document. The service computes Post_Test minus Pre_Test per paired record and averages those differences. Separate unpaired documents do not count as a pair.
4. **Teacher observation (10 features):** add an observation with all required, training-compatible values: Participation, Attention Level, and Confidence each `Low`, `Medium`, or `High`; Collaboration one of `Fair`, `Needs improvement`, `Good`, `Excellent`; Learning Difficulty one of `Addition difficulty`, `Letter formation`, `Listening difficulty`, `None identified`, `Number recognition`, `Pencil control`, `Phonics difficulty`, `Reading difficulty`, `Shape recognition`, `Slow writing`, or `Vocabulary development`; Learning Style one of `Auditory learner`, `Creative learner`, `Logical learner`, `Practical learner`, or `Visual learner`. The four numeric score features are derived from the first four categories; teachers should not invent separate numeric values.
5. **Historical lessons:** no additional history entry is required to make these four features available at the time of this snapshot. All students have valid history metadata. A `Not Started` status is valid history data but does not increment `Completed_Historical_Lessons`.
6. **Worksheet-score contribution:** the scores already entered need no re-entry for their existing worksheet assessment purpose. To make them inform `Average_Score`, the feature pipeline needs an explicit, reviewed mapping from worksheet `assessment_results.score/maxScore` to that feature; this audit did not alter data or infer that mapping.

## Report 4 — Data-mapping and calculation findings

- **Worksheet-to-activity mapping (implemented):** `LiveFeatureService` now combines `learning_records` with `assessment_results` where `worksheet=true` to calculate the five activity features. It normalizes each score by its maxScore, averages attempts and time, counts each included record, and counts only `COMPLETED` rows in the completion numerator. Worksheet `NOT_COMPLETED` is accepted as an incomplete status. When a nonblank `activityId` appears in both sources, the worksheet row is skipped to avoid counting that activity twice.
- **Assessment feature mapping:** `LiveFeatureService` reads measurement keys from `AssessmentResult.measures`. The inspected worksheet documents have `measures: {}`. Their worksheet score, subject, feedback, and completion status are not interchangeable with the eight measured assessment dimensions. No measurement values were found in the live records queried.
- **Activity fields:** no `learning_records` documents exist in this database snapshot. Four worksheet assessments per student provide all activity fields and are now included by the feature service.
- **Teacher observation mapping:** the code reads the latest owner-scoped row from `teacher_observations`. That collection is empty, so neither raw categories nor numeric category conversions are available.
- **Historical lesson mapping:** the code reads `historical_lessons` and uses `lessonId`, `sequenceNumber`, `completionStatus`. All 56 records have recognized status values, nonblank lesson IDs, and sequences ≥ 1, so no mapping or validation bug was found for those four features.
- **Zero handling:** no missing feature was converted to zero. There are no activity scores or measurement values equal to zero to validate as a live zero case. Historical completion counts of `1` are genuine counts; `Not Started` entries are valid and remain non-completed.
- **Readiness gate:** the five worksheet activity features and four historical features are calculable. Prediction remains blocked because 19 assessment-measurement and teacher-observation features are missing; the service still refuses partial snapshots.

## Audit limits

This report reflects the configured local MongoDB `smartplay` database at the snapshot time above. The MongoDB audit used read-only `find`/`aggregate`/count operations and did not edit database records, forms, model files, or training datasets. The feature service was subsequently updated to map worksheet results as documented in Report 4. The three collections queried for this exact feature pipeline were `learning_records`, `assessment_results`, `teacher_observations`, and `historical_lessons`, plus active Grade 1 student records.
