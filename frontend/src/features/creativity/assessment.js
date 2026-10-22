export const teacherCriteria = ["originality", "imagination", "elaboration", "storytelling"];

export function emptyTeacherScores() {
  return Object.fromEntries(teacherCriteria.map((id) => [id, ""]));
}

export function validTeacherScore(value) {
  return Number.isInteger(value) && value >= 1 && value <= 10;
}

export function assessmentFromRecord(record, student) {
  const saved = record.creativityAssessment;
  // Read explicit historical rubric values only. An aggregate cannot recover
  // individual teacher criteria; never manufacture them from the average.
  const teacherNotes = record.notes?.match(/\bRubric:\s*(.*?)(?:\.\s|$)/i)?.[1] ?? "";
  const historical = Object.fromEntries(teacherCriteria.map((id) => {
    const match = teacherNotes.match(new RegExp(`\\b${id}\\s+(\\d+(?:\\.\\d+)?)\\/(\\d+)\\b`, "i"));
    return [id, match && Number(match[2]) === record.maxScore ? Number(match[1]) : null];
  }));
  return {
    id: record.id, recordId: record.id, studentId: record.studentId,
    studentName: student?.name ?? "", activity: saved?.activity || "Creativity assessment",
    prompt: saved?.prompt || "Creativity observation", score: record.score,
    percentage: Math.round(record.score / record.maxScore * 100),
    rubric: saved?.teacherScores ?? historical,
    teacherScoreScale: saved?.teacherScoreScale ?? record.maxScore,
    notes: record.notes || "", completedAt: record.assessedAt,
    imageAnalysis: saved?.imageAnalysis, voiceAnalysis: saved?.voiceAnalysis,
    imageAnalysisError: saved?.imageAnalysisError, voiceAnalysisError: saved?.voiceAnalysisError,
    voiceRecorded: saved?.voiceRecorded,
  };
}
