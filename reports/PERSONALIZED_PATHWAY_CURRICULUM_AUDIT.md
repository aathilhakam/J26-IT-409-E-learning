# Personalized Pathway Curriculum Matching Audit

## Root cause

The live Python selector builds each learning need from a persisted assessment record. For worksheet results, Java uses `subject` (falling back to `worksheetTitle`) as the area label. It compares the resulting words literally with curriculum subject, topic, competency, and learning-objective text. Normalization only lowercases text and removes punctuation; it does not define subject aliases or semantic mappings. Teacher feedback can rank an already-matched lesson, but it cannot supply a missing area match.

An empty result was returned with the same generic status for several distinct failures. That status was copied into the milestone, so Student Profile → Personalized learning pathways had no explanation of the gap.

## Sources inspected

- `AI-Recommendation/data/raw/original_files/original_school_data/2. Curriculum and Lesson Data.xlsx`
- `AI-Recommendation/data/processed/curriculum_clean.csv`
- `AI-Recommendation/data/processed/validated_personalized_learning_paths.csv`
- `AI-Recommendation/data/raw/original_files/teacher_guides/eGr1_2OM Fruits.pdf`, `eGr1_2OM Birds.pdf`, and `eGr01OM Song Book.pdf`
- `AI-Recommendation/pathways.py`, `AI-Recommendation/app.py`
- `LivePathwayService`, `RecommendationMilestoneService`, `LearningRecommendation`, and profile pathway components

The authoritative source workbook and cleaned catalog contain the same 20 Grade 1 lesson rows. The validated pathway list contains 12 distinct lesson IDs. The source worksheets are present by title; text extraction returned no text for the three PDFs, so their content cannot substantiate a more specific curriculum mapping from this checkout.

## Mapping review

| Student evidence area | Curriculum evidence | Result |
| --- | --- | --- |
| Number Recognition | `LES021`, Grade 1 Mathematics, objective “Identify numbers 11-50 correctly”; validated ID | Direct match is supported. The selector can recommend it only when evidence is in this area, `LES021` is not completed, and its required “Number sequence knowledge” skill is recorded. |
| Plants | `LES029`, Grade 1 Environmental Studies, objective “Identify common plants around home and school”; validated ID | Direct plant evidence can match. This does not establish that the lesson teaches fruit-name identification. |
| Fruit names | Worksheet title/subject references fruits; the curriculum only describes common plant identification | No approved fruit-naming mapping. Evidence needed: inspect/approve the worksheet or teacher guide against `LES029`'s actual lesson content, or approve a Grade 1 curriculum objective/activity specifically covering fruit naming. |
| Bird names | No bird topic/objective/activity in the 20-row Grade 1 catalog | No mapping. Evidence needed: an approved Grade 1 bird-identification objective/activity and a validated curriculum lesson ID. |
| Songs, singing, lyrics | No music, singing, or lyrics topic/objective/activity in the catalog | No mapping. Evidence needed: an approved Grade 1 music/song objective/activity and a validated curriculum lesson ID. General listening/story lessons do not prove singing or lyric instruction. |

No curriculum IDs, objectives, or activities were invented, and no alias was added for fruit, bird, or song evidence. The model outcome “Needs Practice” remains a difficulty cap, not a subject selector.

## Filtering and persistence checks

The selector filters out duplicate curriculum IDs, IDs absent from the validated pathway file, wrong-grade lessons, activities above the outcome's difficulty cap, completed lessons, entries without required curriculum text, and lessons whose prerequisite is not an exact normalized match in teacher-recorded demonstrated skills. A lesson that passes selection is tied to the actual assessment evidence ID and gets a rationale containing the measured area and score. Backend persistence uses a deterministic milestone/lesson ID and the existing unique request-key index; new recommendations remain `PENDING` for teacher review and assignment is still approval-gated.

The recommendation model now records the student's grade explicitly. When no candidate passes, the selector returns a per-area reason (missing curriculum mapping, grade mismatch, unvalidated activity, completed lesson, exceeded difficulty, or unconfirmed prerequisite). The milestone stores this reason, and the Student Profile empty state displays it.

## Live data and database effects

This audit did not query or change MongoDB records and did not trigger a recommendation run. Therefore it makes no claim that new pathways were persisted. Existing prediction records, assessment results, feedback, model files, feature schema, and training data were left intact.
