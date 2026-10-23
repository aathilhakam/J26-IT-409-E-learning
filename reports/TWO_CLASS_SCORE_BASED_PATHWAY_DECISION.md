# Score-based outcome and personalized pathway policy

## Model compatibility

The saved Random Forest still returns its original classes: `Successful`, `Needs Practice`, and `Waiting`. These raw predictions and class probabilities remain unchanged and are stored separately from the application outcome. The historical lesson data uses `Waiting` for lessons whose status is `Not Started`; it is therefore exposed as `INSUFFICIENT_DATA` in the legacy two-class compatibility view instead of being treated as a learning diagnosis.

The final application outcome is determined by validated assessment performance, not model probabilities. With valid performance data, the application outcome is `Successful` at **80% or higher** and `Needs Practice` below 80%. When the 28 model features are incomplete or there are no completed valid assessment scores, the workflow reports `INSUFFICIENT_DATA` and does not infer a final outcome.

## Verified performance calculation

The backend reads the student's persisted `assessment_results` rows. It includes completed records with finite scores, a positive maximum score, and `0 <= score <= maximum`. Worksheet rows must also have the authenticated teacher's owner ID; legacy non-worksheet assessment records are tied to the student ID after the service verifies the student's ownership. A recorded zero is valid. Invalid, in-progress, unauthorized worksheet, and malformed rows are excluded. If no valid completed results remain, performance is unavailable rather than zero.

Performance is the arithmetic mean of each distinct included assessment's percentage (`score / maximum * 100`), rounded to two decimals. Re-imported/correction rows for the same worksheet material and assessment date count once, using the latest recorded valid row; non-worksheet rows with the same assessment identifier also count once. The profile reports both the average and number of valid assessments included. This matches the live activity and three-lesson percentage averaging already used by the project. The historic learning-path notebook's weighted `Overall_Performance` expression is not reused because it substitutes absent measures with zero and consumes a historical `Learning_Progress` field that is not part of the live 28-feature schema.

Each evaluated milestone stores a fingerprint of its valid assessment evidence. Repeated evaluations with unchanged evidence reuse the saved result. If valid scores or their assessment areas change, the existing milestone is safely refreshed while its historical record and any already saved recommendations remain persisted. Feature readiness is still checked before a final outcome or new pathway is inferred; an incomplete 28-feature snapshot leaves the application outcome at `INSUFFICIENT_DATA` while retaining the verified score summary for the teacher.

## Targeted practice policy

For application outcome `Needs Practice`, the existing live pathway service uses actual assessment domains and worksheet subjects/titles as evidence. The pathway selector only considers an area with an observed score below the configurable `AREA_PRACTICE_THRESHOLD` (80%). It preserves approved lesson IDs, grade checks, prerequisites, completed activity exclusion, and teacher review. If curriculum does not validate a matching activity, the existing diagnostic explains the missing mapping; no activity is invented.

For `Successful`, the selector returns no remedial activity. Existing recommendations and records are not deleted or closed. For a raw model/application disagreement, the raw class stays visible and the milestone stores a discrepancy message alongside the score-based decision.

## User interface

The shared three-lesson milestone view, shown in the teacher dashboard and student profile, displays the final outcome, verified percentage and valid assessment count, the 80% threshold explanation, any model disagreement, and the pathway-review status. The raw Random Forest class and probabilities remain separate. The exact 28-feature order and preprocessing remain unchanged.
