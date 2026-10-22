import React from "react";

function dateLabel(value) {
  if (!value) return "Date unavailable";
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? String(value) : parsed.toLocaleDateString();
}

export default function ThreeLessonPerformance({ performance, compact = false }) {
  if (!performance?.lessons?.length) return null;
  return <section className={`three-lesson-performance${compact ? " compact" : ""}`} aria-label="Three lesson performance summary">
    <h4>Three lesson performance</h4>
    <div className="three-lesson-stats">
      <span>Average <strong>{Number(performance.averagePercentage).toFixed(1)}%</strong></span>
      <span>Trend <strong>{performance.trend}</strong></span>
      <span>Change <strong>{performance.changePercentagePoints > 0 ? "+" : ""}{Number(performance.changePercentagePoints).toFixed(1)} points</strong></span>
    </div>
    <div className="three-lesson-rows">
      {performance.lessons.map((lesson, index) => <article key={`${lesson.materialId}-${lesson.assessmentResultId || index}`}>
        <div><strong>Lesson {index + 1}: {lesson.worksheetTitle || "Worksheet"}</strong><small>{dateLabel(lesson.assessmentDate || lesson.completedAt)}{lesson.subject ? ` · ${lesson.subject}` : ""}</small></div>
        <strong>{lesson.score} / {lesson.maxScore} · {Number(lesson.percentage).toFixed(1)}%</strong>
        {lesson.teacherFeedback && <p>Teacher feedback: {lesson.teacherFeedback}</p>}
      </article>)}
    </div>
  </section>;
}
