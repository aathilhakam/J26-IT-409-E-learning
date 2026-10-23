import React,{useEffect,useState} from "react";
import {Card} from "../../../components";
import {api} from "../../../services/api";
import RecommendationCard from "./RecommendationCard";
import {PROTOTYPE_SUCCESS_THRESHOLD} from "../prototypePolicy";

export function prototypePathwayDecision(prediction, threshold = PROTOTYPE_SUCCESS_THRESHOLD) {
 const probabilities=prediction?.probabilities||{};
 const successful=probabilities.Successful,needsPractice=probabilities["Needs Practice"];
 if(!["Successful","Needs Practice"].includes(prediction?.predictedClass)
   ||!Number.isFinite(successful)||!Number.isFinite(needsPractice)
   ||successful<0||successful>1||needsPractice<0||needsPractice>1
   ||Math.abs(successful+needsPractice-1)>1e-6)return null;
 return {ready:successful>=threshold,successfulProbability:successful,predictedClass:prediction.predictedClass};
}

export function prototypePracticeAreas(moduleScores, threshold = 75) {
 const grouped=new Map();
 for(const row of moduleScores||[]){
  const maximum=Number(row.maxScore),score=Number(row.score);
  if(!Number.isFinite(maximum)||maximum<=0||!Number.isFinite(score)||score<0||score>maximum)continue;
  if(row.completionStatus&&!(["COMPLETED","Completed"].includes(row.completionStatus)))continue;
  const key=row.materialId||(row.assessmentId?`assessment:${row.assessmentId}`:row.worksheetTitle||row.subject||row.assessmentType);
  if(!key)continue;
  const current=grouped.get(key)||{id:key,title:row.worksheetTitle||row.assessmentId||row.assessmentType||row.subject||"Learning module",subject:row.subject,score:0,maxScore:0};
  current.score+=score;current.maxScore+=maximum;grouped.set(key,current);
 }
 return [...grouped.values()].map(module=>({...module,percentage:module.score/module.maxScore*100}))
  .filter(module=>module.percentage<threshold).sort((a,b)=>a.percentage-b.percentage);
}

export default function StudentPathways({student,materials=[],moduleScores=[],teacherFeedback=[],feedbackLoading=false,feedbackError="",initial=[],initialMilestones=[],onChanged}){
 const [rows,setRows]=useState(initial),[milestones,setMilestones]=useState(initialMilestones),[error,setError]=useState(""),[busy,setBusy]=useState(false),[editing,setEditing]=useState("");
 const [prototype,setPrototype]=useState(null),[prototypeError,setPrototypeError]=useState(""),[prototypeBusy,setPrototypeBusy]=useState(false);
 useEffect(()=>{let active=true;async function load(){const results=await Promise.allSettled([api.recommendations(student.id),api.milestoneProgress(student.id)]);if(!active)return;if(results[0].status==="fulfilled")setRows(results[0].value);else setError(results[0].reason.message);if(results[1].status==="fulfilled")setMilestones(results[1].value.milestones || []);}void load();const refresh=()=>{if(document.visibilityState!=="hidden")void load();};const timer=setInterval(refresh,15000);window.addEventListener("smartplay-evidence-updated",refresh);return()=>{active=false;clearInterval(timer);window.removeEventListener("smartplay-evidence-updated",refresh);};},[student.id]);
 useEffect(()=>{let active=true;const evaluate=async()=>{setPrototypeBusy(true);setPrototypeError("");try{const result=await api.predictBinaryPrototype(student.id);if(active)setPrototype(result);}catch(cause){if(active){setPrototype(null);setPrototypeError(cause.message||"Experimental pathway readiness is unavailable.");}}finally{if(active)setPrototypeBusy(false);}};setPrototype(null);void evaluate();const refresh=()=>{if(document.visibilityState!=="hidden")void evaluate();};window.addEventListener("smartplay-evidence-updated",refresh);return()=>{active=false;window.removeEventListener("smartplay-evidence-updated",refresh);};},[student.id]);
 async function action(work){if(busy)return;setBusy(true);setError("");try{await work();setRows(await api.recommendations(student.id));setEditing("");await onChanged?.(student.id);}catch(e){setError(e.message);}finally{setBusy(false);}}
 const noMatch=milestones.find(m=>m.status==="NO_MATCH" && m.message);
 const decision=prototypePathwayDecision(prototype),practiceAreas=prototypePracticeAreas(moduleScores);
 const savedTeacherFeedback=[...teacherFeedback].filter(item=>item.submissionStatus==="SUBMITTED").sort((a,b)=>new Date(b.submittedAt||0)-new Date(a.submittedAt||0));
 return <Card as="section" className="student-profile-card student-profile-recommendations"><span className="dashboard-section-kicker">PERSONALIZED LEARNING</span><h2>Personalized learning pathways</h2><p>Curriculum-selected activities for {student.name} · {student.grade}. The experimental two-class model provides a readiness signal; completed saved assessment records identify subject areas for practice.</p>
  <section className={`prototype-pathway-status ${decision?(decision.ready?"pathway-guidance-ready":"pathway-guidance-practice"):""}`} aria-label="Experimental pathway readiness">
   <h3>Experimental model pathway guidance</h3>
   {prototypeBusy?<p role="status">Checking the saved experimental model features…</p>:prototypeError?<p className="teacher-note" role="alert">Prototype guidance unavailable: {prototypeError}</p>:decision?.ready?<div role="status"><strong>Ready to next learning path</strong><p>Successful class probability: {(decision.successfulProbability*100).toFixed(1)}% ({PROTOTYPE_SUCCESS_THRESHOLD*100}% or higher). Review the next grade-appropriate pathway below with the teacher.</p></div>:decision?<div role="status"><strong>Practice recommended before the next learning path</strong><p>Successful class probability: {(decision.successfulProbability*100).toFixed(1)}% (below {PROTOTYPE_SUCCESS_THRESHOLD*100}%).</p>{practiceAreas.length?<><p>Practice focus areas from completed saved assessment evidence:</p><ul>{practiceAreas.map(module=><li key={module.id}><strong>{module.title}</strong>{module.subject?` · ${module.subject}`:""}: {module.score} / {module.maxScore} ({module.percentage.toFixed(1)}%)</li>)}</ul></>:<p>No completed saved assessment area below 75% is available. The prototype does not identify a subject area, so record completed assessment evidence before targeting practice.</p>}<section className="pathway-teacher-feedback" aria-label="Saved teacher feedback for practice support"><h4>Saved teacher feedback</h4>{feedbackLoading?<p role="status">Loading submitted teacher feedback…</p>:feedbackError?<p role="alert">Could not load submitted teacher feedback: {feedbackError}</p>:savedTeacherFeedback.length?savedTeacherFeedback.map(item=>{const observation=item.observation||{};return <article key={item.id}><h5>{item.lessonTitle||"Lesson"}{item.submittedAt?` · ${new Date(item.submittedAt).toLocaleString()}`:""}</h5><dl><div><dt>Participation</dt><dd>{observation.participation||"—"}</dd></div><div><dt>Attention Level</dt><dd>{observation.attentionLevel||"—"}</dd></div><div><dt>Confidence</dt><dd>{observation.confidence||"—"}</dd></div><div><dt>Collaboration</dt><dd>{observation.collaboration||"—"}</dd></div><div><dt>Learning Difficulty</dt><dd>{observation.learningDifficulty||"—"}</dd></div><div><dt>Learning Style</dt><dd>{observation.learningStyle||"—"}</dd></div><div><dt>Improvement Observed</dt><dd>{item.improvement||"—"}</dd></div><div><dt>Feedback / Next Step</dt><dd>{item.feedback||"—"}</dd></div></dl></article>}):<p>No submitted teacher feedback is saved for this student.</p>}</section></div>:<p role="status">The prototype response did not include valid probabilities for both supported classes.</p>}
   {prototype&&<p className="teacher-note">Exploratory only · raw prototype class: {prototype.predictedClass} · model version: {prototype.modelVersion || "not recorded"} · {prototype.warning}</p>}
  </section>
  {error && <p role="alert">{error}</p>}{rows.length?rows.map(r=><React.Fragment key={r.id}>{r.assignedAt && r.materialId && <p>Assigned material: <a href={`/dashboard/lessons/${encodeURIComponent(r.materialId)}`}>{materials.find(m=>m.id===r.materialId)?.title || "Open learning material"}</a></p>}<RecommendationCard recommendation={r} showPrediction={false} materials={materials} saving={busy} editing={editing===r.id} onEdit={()=>setEditing(r.id)} onCancelEdit={()=>setEditing("")} onReview={(status,data)=>action(()=>api.reviewRecommendation(r.id,{status,...data}))} onAssign={materialId=>action(()=>api.assignRecommendation(r.id,materialId))} /></React.Fragment>):noMatch?<div role="status" className="pathway-gap"><strong>No pathway saved · curriculum match needed</strong><p>{noMatch.message}</p><p>Recommendations are saved only when an approved, grade-appropriate activity matches the student's evidence, grade, and prerequisites.</p></div>:<p>No saved pathway yet. A recommendation will appear after the workflow finds an approved activity that matches the student's evidence, grade, and prerequisites.</p>}
 </Card>;
}
