import WorksheetPrintActions from "../../worksheets/WorksheetPrintActions";
import React from "react";
import { Button, Card } from "../../../components";
import { displayDate } from "../../../utils/format";

export default function ClassroomLessons({ state, onOpenLesson, availableMaterials = [], onAssignMaterial }) {
  function open(event, id) {
    if (onOpenLesson && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey) { event.preventDefault(); onOpenLesson(id); }
  }
  function lessonCard(lesson, index, history = false) {
    return <article className="classroom-lesson" key={lesson.id}>
      <p className="dashboard-section-kicker">{history ? "Completed lesson" : index === 0 ? "Current lesson" : "Next lesson"}</p>
      <div className="classroom-lesson-heading"><h3><a href={`/dashboard/lessons/${encodeURIComponent(lesson.id)}`} onClick={event => open(event, lesson.id)}>{lesson.title}</a></h3><span className="dashboard-activity-chip">Grade group · {lesson.assignmentGrade || lesson.grade || "Not specified"}</span></div>
      {lesson.originalFileName && <p className="teacher-note lesson-filename">{lesson.originalFileName}</p>}
      {lesson.description && <p>{lesson.description}</p>}
      <p className="teacher-note">{lesson.subject || "Subject not specified"} · {lesson.grade || "Grade not specified"} · {lesson.materialType || "Material"}</p>
      <small>{history ? lesson.completedAt ? `Completed ${displayDate(lesson.completedAt)}` : "Completion date not recorded" : lesson.assignedAt ? `Assigned ${displayDate(lesson.assignedAt)}` : `Assignment date not recorded · uploaded ${displayDate(lesson.uploadedAt)}`}</small>
      <div className="classroom-counts" aria-label="Lesson completion"><span>{lesson.assigned} assigned</span><span>{lesson.completed} completed</span></div>
      {!history && index === 0 && /worksheet|activity sheet/i.test(`${lesson.materialType || ""} ${lesson.category || ""}`) && <WorksheetPrintActions materialId={lesson.id} title={lesson.title} onView={()=>onOpenLesson?.(lesson.id)} />}
      {!history && lesson.assigned === 0 && <p className="teacher-note">No students assigned. Assign students in Upload &amp; Content before this lesson can complete.</p>}
      <a className="lesson-open-link" href={`/dashboard/lessons/${encodeURIComponent(lesson.id)}`} onClick={event => open(event, lesson.id)}>{history ? "Review completed lesson" : index === 0 ? "Open Current Lesson" : "Preview next lesson"}<span className="sr-only"> for {lesson.title}</span> →</a>
    </article>;
  }
  return <Card as="section" className="dashboard-activity-panel classroom-lessons" aria-busy={Boolean(state?.loading)}>
    <details className="completed-lessons-history recent-learning-activity" open><summary>Recent learning activity <span className="dashboard-activity-chip">{state?.lessons?.length || availableMaterials.length}</span></summary>
      <p className="teacher-note">Choose a learner to see their learning activity.</p>
      {state?.loading ? <p role="status">Loading classroom lessons…</p> : state?.error ? <div role="alert"><p>{state.error}</p><Button onClick={state.refresh}>Retry classroom data</Button></div> : !state?.lessons?.length ? <>
        {availableMaterials.length === 0 && <p className="teacher-note">No classroom lessons assigned yet.</p>}
        {availableMaterials.length > 0 && <div aria-label="Next learning materials to assign">{availableMaterials.slice(0, 2).map((material, index) => <article className="classroom-lesson" key={material.id}>
          <p className="dashboard-section-kicker">{index === 0 ? "Next lesson" : "Following lesson"}</p>
          <div className="classroom-lesson-heading"><h3>{material.title}</h3><span className="dashboard-activity-chip">Grade group · {material.grade || "Choose when assigning"}</span></div>
          {material.originalFileName && <p className="teacher-note lesson-filename">{material.originalFileName}</p>}
          {material.description && <p>{material.description}</p>}
          <p className="teacher-note">{material.subject || "Subject not specified"} · {material.grade || "Grade not specified"} · {material.materialType || "Material"}</p>
          <small>Available in your private library · Not assigned yet</small>
          <div className="classroom-counts" aria-label="Lesson assignment"><span>Ready to assign</span></div>
          <button type="button" className="lesson-open-link" onClick={() => onAssignMaterial?.(material)}>Assign lesson<span className="sr-only"> {material.title}</span> →</button>
        </article>)}</div>}
      </> : <>
        {state.lessons.slice(0, 2).map((lesson, index) => lessonCard(lesson, index))}
        {state.lessons.length > 2 && <p className="teacher-note">{state.lessons.length - 2} more lessons queued. The next lesson appears when every assigned activity in this week’s lesson is complete.</p>}
      </>}
    </details>
    <details className="completed-lessons-history"><summary>Completed Lessons History <span className="dashboard-activity-chip">{state?.history?.length || 0}</span></summary>
      {state?.history?.length ? state.history.map(lesson => lessonCard(lesson, 0, true)) : <p className="teacher-note">No completed lessons for this grade group yet.</p>}
    </details>
  </Card>;
}
