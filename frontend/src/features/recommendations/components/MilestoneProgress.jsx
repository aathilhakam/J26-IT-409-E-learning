import React, { useEffect, useRef, useState } from "react";
import { Button, Card } from "../../../components";
import { api } from "../../../services/api";
import RecommendationCard from "./RecommendationCard";
import ThreeLessonPerformance from "./ThreeLessonPerformance";

export const milestoneStatus = value => ({GENERATING:"Generating recommendation",READY:"Ready to evaluate",GENERATED:"Generated · teacher review required",INSUFFICIENT_DATA:"More learning evidence needed",ERROR:"Evaluation failed · retry available",NO_MATCH:"No suitable curriculum activity"}[value] || value);
export function MilestoneSummary({ progress }) {
  return <p><strong>{progress.lessonsTowardNext} of 3 lessons completed</strong> toward milestone {progress.nextMilestone} · {progress.completedLessons} eligible completed lessons in total.</p>;
}
export function milestoneReviewStatus(milestone,recommendations) {
  if(milestone.status !== "GENERATED")return milestoneStatus(milestone.status);
  const rows=recommendations.filter(r=>r.completionMilestone===milestone.milestone);
  if(rows.some(r=>r.status==="PENDING"))return "Awaiting teacher review";
  if(rows.some(r=>r.assignedAt))return "Approved activities assigned";
  if(rows.some(r=>r.status==="APPROVED"))return "Approved · ready to assign";
  return rows.length ? "Rejected by teacher" : "Generated";
}
export default function MilestoneProgress({ studentId, materials = [], onChanged, showRecommendations = true }) {
  const [state,setState]=useState(null),[error,setError]=useState(""),[busy,setBusy]=useState(false),[editing,setEditing]=useState("");
  const generation=useRef(0), changed=useRef(onChanged);changed.current=onChanged;
  async function load() {
    const request=++generation.current;
    try{const data=await api.milestoneProgress(studentId);if(request===generation.current){setState(data);setError("");}}
    catch(cause){if(request===generation.current)setError(cause.message || "Could not load lesson milestones.");}
  }
  useEffect(()=>{
    setState(null);setError("");if(!studentId)return;
    void load();const refresh=()=>{if(document.visibilityState!=="hidden")void load();};
    const timer=setInterval(refresh,15000);window.addEventListener("focus",refresh);window.addEventListener("smartplay-evidence-updated",refresh);
    return ()=>{generation.current++;clearInterval(timer);window.removeEventListener("focus",refresh);window.removeEventListener("smartplay-evidence-updated",refresh);};
  },[studentId]);
  async function action(work) {
    if(busy)return;setBusy(true);setError("");
    try{await work();await load();changed.current?.();}
    catch(cause){setError(cause.message || "Could not update recommendation.");}
    finally{setBusy(false);}
  }
  return <Card as="section" className="milestone-progress" aria-label="Three-lesson recommendations" aria-busy={busy}>
    <span className="dashboard-section-kicker">ADAPTIVE LEARNING</span><h2>Pathway after every 3 lessons</h2>
    {!studentId ? <p>Select a student to see their own lesson milestones and reviewed pathways.</p> : <>
      {error && <p role="alert">{error}<Button variant="quiet" onClick={load}>Retry milestone progress</Button></p>}
      {!state ? <p role="status">Loading this student’s completed lessons…</p> : <>
        <MilestoneSummary progress={state} />
        <p className="teacher-note">A lesson counts once when its student assignment is completed and a valid worksheet score is saved. Recommendations stay unassigned until teacher approval.</p>
        <Button variant="secondary" disabled={busy || state.completedLessons<3} onClick={()=>action(()=>api.evaluateMilestones(studentId))}>{busy ? "Evaluating…" : "Evaluate / retry eligible milestones"}</Button>
        <div className="milestone-history">{state.milestones.map(m=><details key={m.id}><summary>After {m.milestone} lessons · {milestoneReviewStatus(m,state.recommendations || [])}</summary><p>{m.message}</p>
          <ThreeLessonPerformance performance={m.threeLessonPerformance} />
          {m.modelVersion && <p className="teacher-note">Prediction: {m.predictedOutcome} · Model version: {m.modelVersion}</p>}
          <p className="teacher-note">{m.evidenceIds?.length || 0} saved evidence references{m.featureSnapshot?.generatedAt ? ` · Features generated ${new Date(m.featureSnapshot.generatedAt).toLocaleString()}` : ""}</p>
          {m.featureSnapshot && <details><summary>View model features and evidence</summary><dl className="milestone-features">{Object.entries(m.featureSnapshot.features || {}).map(([key,value])=><React.Fragment key={key}><dt>{key}</dt><dd>{String(value)}</dd></React.Fragment>)}</dl><p className="teacher-note">{m.evidenceIds.join(" · ")}</p></details>}
          {showRecommendations && (state.recommendations || []).filter(r=>r.completionMilestone===m.milestone).map(r=><React.Fragment key={r.id}>{r.assignedAt && r.materialId && <p>Assigned learning material: <a href={`/dashboard/lessons/${encodeURIComponent(r.materialId)}`}>{materials.find(item=>item.id===r.materialId)?.title || "Open assigned material"}</a></p>}<RecommendationCard recommendation={r} materials={materials} saving={busy} editing={editing===r.id} onEdit={()=>setEditing(r.id)} onCancelEdit={()=>setEditing("")} onReview={(status,data)=>action(async()=>{await api.reviewRecommendation(r.id,{status,...data});setEditing("");})} onAssign={materialId=>action(()=>api.assignRecommendation(r.id,materialId))} /></React.Fragment>)}
        </details>)}</div>
      </>}
    </>}
  </Card>;
}
