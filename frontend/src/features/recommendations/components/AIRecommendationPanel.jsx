import React, { useEffect, useState } from "react";
import { Card, Button } from "../../../components";
import { api } from "../../../services/api";

export function predictionExplanation(label) {
  const policy={Waiting:"Beginner activities", "Needs Practice":"Beginner or Basic activities",Successful:"Beginner, Basic or Intermediate activities"};
  const training="The model was trained to predict the most frequent Outcome recorded for each student in the Historical Learning Path dataset. The project does not define a scoring rubric for the Outcome categories.";
  return policy[label] ? `${training} “${label}” is that historical category. The separate curriculum policy allows ${policy[label]} for this class; that policy does not describe this student's measured ability or diagnose a learning difficulty.` : `The model returned “${label}”. ${training} No more specific interpretation is documented; review the supporting evidence before drawing conclusions.`;
}
export function latestPrediction(recommendations,progress) {
  const candidates=recommendations.filter(r=>r.source==="RANDOM_FOREST" && r.predictedOutcome).map(r=>({label:r.predictedOutcome,probabilities:r.predictionProbabilities,version:r.modelVersion,at:r.featuresGeneratedAt || r.createdAt,evidence:r.sourceEvidenceIds?.length?r.sourceEvidenceIds:[r.evidenceId].filter(Boolean),snapshot:progress?.milestones?.find(m=>m.milestone===r.completionMilestone)?.featureSnapshot}));
  for(const m of progress?.milestones || [])if(m.predictedOutcome)candidates.push({label:m.predictedOutcome,probabilities:m.probabilities,version:m.modelVersion,at:m.featureSnapshot?.generatedAt || m.updatedAt,evidence:m.evidenceIds,snapshot:m.featureSnapshot});
  return candidates.sort((a,b)=>new Date(b.at || 0)-new Date(a.at || 0))[0] || null;
}
export default function AIRecommendationPanel({ studentId }) {
  const [data,setData]=useState(null),[error,setError]=useState("");
  useEffect(()=>{
    let active=true;setData(null);setError("");if(!studentId)return;
    async function load(){const responses=await Promise.allSettled([api.recommendations(studentId),api.milestoneProgress(studentId),api.liveFeatures(studentId)]);if(!active)return;
      const recs=responses[0].status==="fulfilled"?responses[0].value:[];const progress=responses[1].status==="fulfilled"?responses[1].value:null;
      setData({prediction:latestPrediction(recs,progress),features:responses[2].status==="fulfilled"?responses[2].value:null,missing:responses[2].status==="rejected"?responses[2].reason.message:""});
      setError(responses[0].status==="rejected"?responses[0].reason.message:responses[1].status==="rejected"?responses[1].reason.message:"");
    }
    void load();const refresh=()=>{if(document.visibilityState!=="hidden")void load();};const timer=setInterval(refresh,15000);window.addEventListener("smartplay-evidence-updated",refresh);window.addEventListener("focus",refresh);
    return()=>{active=false;clearInterval(timer);window.removeEventListener("smartplay-evidence-updated",refresh);window.removeEventListener("focus",refresh);};
  },[studentId]);
  const prediction=data?.prediction;
  return <Card as="section" className="student-profile-card ai-prediction-panel"><span className="dashboard-section-kicker">RANDOM FOREST AI</span><h2>Random Forest prediction</h2>
    <p>Latest saved model prediction. Personalized activities and teacher decisions appear separately below.</p>
    {!studentId?<p>Select a student to view their model evidence.</p>:!data?<p role="status">Loading saved prediction and evidence…</p>:<>
      {error && <p role="alert">{error}</p>}
      {data.missing && <p role="status">{data.missing}</p>}
      {prediction?<><h3>Predicted class: {prediction.label}</h3><p>{predictionExplanation(prediction.label)}</p>
        <div aria-label="Class probabilities">{Object.entries(prediction.probabilities || {}).filter(([,p])=>Number.isFinite(p)&&p>=0&&p<=1).map(([label,p])=><p key={label}>{label}: <strong>{(p*100).toFixed(1)}%</strong> class probability</p>)}</div>
        <p className="teacher-note">Model version: {prediction.version || "Not recorded"} · Prediction evidence timestamp: {prediction.at?new Date(prediction.at).toLocaleString():"Not recorded"}</p>
        <details><summary>Prediction evidence</summary><p className="teacher-note">{prediction.evidence?.join(" · ") || "Source references not recorded"}</p>{prediction.snapshot && <FeatureList snapshot={prediction.snapshot} />}</details>
      </>:<p>No saved Random Forest prediction yet. The existing three-lesson workflow evaluates eligible students when sufficient measured evidence is available.</p>}
      {data.features && <details><summary>Available current model features</summary><p>These current features may be newer than the saved prediction.</p><FeatureList snapshot={data.features} /></details>}
    </>}
  </Card>;
}
function FeatureList({snapshot}){return <dl className="milestone-features">{Object.entries(snapshot.features || {}).map(([key,value])=><React.Fragment key={key}><dt>{key}</dt><dd>{String(value)}</dd></React.Fragment>)}</dl>;}
