export const LEVELS = ["Low", "Medium", "High"];
export const OBSERVATION_FIELDS = [
  ["participation", "Participation", LEVELS], ["attentionLevel", "Attention level", LEVELS],
  ["confidence", "Confidence", LEVELS], ["collaboration", "Collaboration", ["Fair", "Needs improvement", "Good", "Excellent"]],
  ["learningDifficulty", "Learning difficulty", ["Addition difficulty", "Letter formation", "Listening difficulty", "None identified", "Number recognition", "Pencil control", "Phonics difficulty", "Reading difficulty", "Shape recognition", "Slow writing", "Vocabulary development"]],
  ["learningStyle", "Learning style", ["Auditory learner", "Creative learner", "Logical learner", "Practical learner", "Visual learner"]],
];
export const MEASURES = ["Pre_Test", "Post_Test", "Unit_Test", "Monthly_Test", "Term_Result", "Motor_Skills_Score", "Creativity_Score", "Interactive_Engagement"];
export const STATUSES = ["Completed", "Not Started", "In Progress"];
export const COMPONENTS = ["MOTOR_SKILLS", "CREATIVITY_THINKING", "TEACHER_ASSESSMENT", "CLASSROOM_ACTIVITY", "WORKSHEET_ASSESSMENT", "PARENT_REINFORCEMENT", "OTHER"];
export function evidencePayload(kind, values, now = Date.now()) {
  const text = (name) => String(values[name] ?? "").trim();
  const required = (name) => { const value = text(name); if (!value) throw new Error("Complete all required fields."); return value; };
  const choice = (name, choices) => { const value = required(name); if (!choices.includes(value)) throw new Error("Choose a supported option."); return value; };
  const number = (name, min, max = Infinity, integer = false) => { const value = Number(required(name)); if (!Number.isFinite(value) || value < min || value > max || (integer && !Number.isInteger(value))) throw new Error(`${name.replaceAll("_", " ")} must be ${integer ? "a whole number" : "a number"} between ${min} and ${max === Infinity ? "the allowed maximum" : max}.`); return value; };
  const date = (name) => { if (!text(name)) return {}; const value = new Date(text(name)); if (!Number.isFinite(value.getTime()) || value.getTime() > now) throw new Error("Choose a valid date and time that is not in the future."); return { [name]: value.toISOString() }; };
  if (kind === "observation") {
    const payload = Object.fromEntries(OBSERVATION_FIELDS.map(([key, , choices]) => [key, choice(key, choices)]));
    const skills = text("demonstratedSkills").split("\n").map(s => s.trim()).filter(Boolean);
    if (skills.length > 200 || skills.some(s => s.length > 180)) throw new Error("Use at most 200 skills, each up to 180 characters.");
    return { ...payload, demonstratedSkills: [...new Set(skills)], ...date("observedAt") };
  }
  if (kind === "history") {
    const lessonId = required("lessonId"); if (lessonId.length > 180) throw new Error("Lesson identifier must be at most 180 characters.");
    return { lessonId, sequenceNumber: number("sequenceNumber", 1, 2147483647, true), completionStatus: choice("completionStatus", STATUSES), ...date("occurredAt") };
  }
  const score = number("score", 0), maxScore = number("maxScore", 0.1);
  if (score > maxScore) throw new Error("Score cannot exceed maximum score.");
  const measures = Object.fromEntries(MEASURES.filter(key => text(key) !== "").map(key => [key, number(key, 0, 100)]));
  return { sourceComponent: choice("sourceComponent", COMPONENTS), assessmentId: required("assessmentId"), assessmentType: text("assessmentType") || null,
    score, maxScore, completionStatus: choice("completionStatus", ["COMPLETED", "IN_PROGRESS", "NOT_COMPLETED"]), notes: text("notes"), measures,
    ...(text("attempts") ? { attempts: number("attempts", 0, 2147483647, true) } : {}), ...(text("timeTakenMinutes") ? { timeTakenMinutes: number("timeTakenMinutes", 0) } : {}) };
}

// A synchronous lock prevents a second click before React renders the busy state.
export function createEvidenceSubmission() {
  let pending = false;
  return async ({ kind, values, student, save, onState, onSaved }) => {
    if (pending) return;
    if (!student?.id || student.active === false) { onState({ error: "Select an active student before saving." }); return; }
    let payload;
    try { payload = evidencePayload(kind, values); } catch (error) { onState({ error: error.message }); return; }
    pending = true; onState({ busy: true });
    try { await save(student.id, payload); }
    catch (error) { pending = false; onState({ error: error.status === 403 ? "You are not authorized to save evidence for this student." : error.status === 409 ? "This student is archived. Select an active student." : error.message || "Could not save evidence." }); return; }
    onState({ busy: true, success: "Evidence saved successfully." });
    try { await onSaved?.(); }
    catch { onState({ success: "Evidence saved successfully.", error: "Refresh failed. Reload the profile before adding more evidence." }); return; }
    finally { pending = false; }
    onState({ success: "Evidence saved successfully." });
  };
}
