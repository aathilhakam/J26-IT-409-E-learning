import test from "node:test";
import assert from "node:assert/strict";
import { assessmentFromRecord, emptyTeacherScores, validTeacherScore } from "./assessment.js";

test("new teacher rubric is blank and accepts only integers 1–10", () => {
  assert.deepEqual(Object.values(emptyTeacherScores()), ["", "", "", ""]);
  for (const invalid of ["", null, "3", 0, 11, 1.5, NaN, Infinity]) assert.equal(validTeacherScore(invalid), false);
  for (let score = 1; score <= 10; score++) assert.equal(validTeacherScore(score), true);
});

test("historical notes preserve explicit scores and their scale without inferring missing criteria", () => {
  const old = assessmentFromRecord({ id: "old", score: 4, maxScore: 5, notes: "Rubric: Originality 3/5, Imagination 5/5. Image estimates: detail 4/5" });
  assert.equal(old.teacherScoreScale, 5);
  assert.deepEqual(old.rubric, { originality: 3, imagination: 5, elaboration: null, storytelling: null });
  assert.equal(old.percentage, 80);
});

test("reload retains structured teacher scores independently of image results", () => {
  const teacherScores = { originality: 10, imagination: 1, elaboration: 6, storytelling: 8 };
  const imageAnalysis = { scores: { originality: 2, color_exploration: 3 } };
  const saved = assessmentFromRecord({ id: "new", score: 6.25, maxScore: 10,
    creativityAssessment: { teacherScores, teacherScoreScale: 10, imageAnalysis } });
  assert.deepEqual(saved.rubric, teacherScores);
  assert.equal(saved.imageAnalysis, imageAnalysis);
  assert.equal(saved.teacherScoreScale, 10);
});

test("AI-only historical notes never populate teacher criteria", () => {
  const saved = assessmentFromRecord({ score: 4, maxScore: 5, notes: "Image estimates: originality 4/5, elaboration 5/5." });
  assert.ok(Object.values(saved.rubric).every((value) => value === null));
});
