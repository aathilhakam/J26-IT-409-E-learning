import React, { useEffect, useState } from "react";
import { Card, Button } from "../../../components";
import { api } from "../../../services/api";
import { PROTOTYPE_SUCCESS_THRESHOLD } from "../prototypePolicy";

export function predictionExplanation(label) {
  const policy={Waiting:"Beginner activities", "Needs Practice":"Beginner or Basic activities",Successful:"Beginner, Basic or Intermediate activities"};
  const training="The model was trained to predict the most frequent Outcome recorded for each student in the Historical Learning Path dataset. The project does not define a scoring rubric for the Outcome categories.";
  return policy[label] ? `${training} “${label}” is that historical category. The separate curriculum policy allows ${policy[label]} for this class; that policy does not describe this student's measured ability or diagnose a learning difficulty.` : `The model returned “${label}”. ${training} No more specific interpretation is documented; review the supporting evidence before drawing conclusions.`;
}
export function latestPrediction(recommendations,progress) {
  const milestones=progress?.milestones || [];
  const modelCandidates=recommendations.filter(r=>r.source==="RANDOM_FOREST" && r.predictedOutcome).map(r=>({label:r.predictedOutcome,probabilities:r.predictionProbabilities,version:r.modelVersion,at:r.featuresGeneratedAt || r.createdAt,evidence:r.sourceEvidenceIds?.length?r.sourceEvidenceIds:[r.evidenceId].filter(Boolean),snapshot:milestones.find(m=>m.milestone===r.completionMilestone)?.featureSnapshot}));
  for(const m of milestones)if(m.predictedOutcome)modelCandidates.push({label:m.predictedOutcome,probabilities:m.probabilities,version:m.modelVersion,at:m.updatedAt || m.featureSnapshot?.generatedAt,evidence:m.evidenceIds,snapshot:m.featureSnapshot});
  // Keep the score decision independent from the model timestamp. Feature generation
  // timestamps can predate milestone completion and must not hide a refreshed outcome.
  const latestOutcome=[...milestones].filter(m=>m.applicationOutcome).sort((a,b)=>new Date(b.updatedAt || 0)-new Date(a.updatedAt || 0))[0];
  const model=modelCandidates.sort((a,b)=>new Date(b.at || 0)-new Date(a.at || 0))[0] || {};
  if(!model.label&&!latestOutcome)return null;
  return {...model,applicationOutcome:latestOutcome?.applicationOutcome || null,
    verifiedPerformancePercentage:latestOutcome?.verifiedPerformancePercentage ?? null,
    verifiedAssessmentCount:latestOutcome?.verifiedAssessmentCount ?? null,
    assessmentEvidence:latestOutcome?.assessmentEvidence || [],
    discrepancy:latestOutcome?.outcomeDiscrepancy || (latestOutcome?.applicationOutcome && model.label && latestOutcome.applicationOutcome!==model.label ? `Random Forest predicted ${model.label}; verified score policy classified ${latestOutcome.applicationOutcome}.` : null),
    outcomeUpdatedAt:latestOutcome?.updatedAt || null,
    pathwayStatus:latestOutcome?.status || null,
    pathwayMessage:latestOutcome?.message || null};
}
export function predictionModelClassStatus(probabilities) {
  const classes = Object.keys(probabilities || {}).sort();
  if (classes.length === 2 && classes[0] === "Needs Practice" && classes[1] === "Successful") {
    return "Active model classes: Needs Practice and Successful.";
  }
  if (classes.includes("Waiting")) {
    return "Active model is the original three-class Random Forest (Needs Practice, Successful, Waiting). No validated binary candidate is deployed.";
  }
  return classes.length ? `Active model classes: ${classes.join(", ")}. This class set is not the approved two-class model.` : "Active model class information is unavailable; binary deployment is not confirmed.";
}
export function probabilityThresholdOutcome(probabilities, threshold = 0.75) {
  const successful = Number(probabilities?.Successful);
  if (!Number.isFinite(successful) || successful < 0 || successful > 1) return null;
  return { probability: successful, outcome: successful > threshold ? "Successful" : "Needs Practice" };
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
  const savedProbabilityOutcome=probabilityThresholdOutcome(prediction?.probabilities);
  return <Card as="section" className="student-profile-card ai-prediction-panel"><span className="dashboard-section-kicker">RANDOM FOREST AI</span><h2>Random Forest prediction</h2>
    {!studentId?<p>Select a student to view their model evidence.</p>:!data?<p role="status">Loading saved prediction and evidence…</p>:<>
      {error && <p role="alert">{error}</p>}
      {prediction?.label?<><section aria-label="Random Forest model prediction"><h3>Random Forest predicted class: {prediction.label}</h3><p>{predictionExplanation(prediction.label)}</p>
        <div aria-label="Class probabilities">{Object.entries(prediction.probabilities || {}).filter(([,p])=>Number.isFinite(p)&&p>=0&&p<=1).map(([label,p])=><p key={label}>{label}: <strong>{(p*100).toFixed(1)}%</strong> class probability</p>)}</div>
        {savedProbabilityOutcome && <p><strong>75% Successful-probability rule:</strong> {savedProbabilityOutcome.outcome} ({(savedProbabilityOutcome.probability*100).toFixed(1)}% Successful class probability; above 75% is Successful).</p>}
        <p className="teacher-note">{predictionModelClassStatus(prediction.probabilities)}</p>
        <p className="teacher-note">Model version: {prediction.version || "Not recorded"} · Prediction timestamp: {prediction.at?new Date(prediction.at).toLocaleString():"Not recorded"}</p>
        <details open><summary>Prediction evidence</summary><p className="teacher-note">{prediction.evidence?.join(" · ") || "Source references not recorded"}</p>{prediction.snapshot && <FeatureList snapshot={prediction.snapshot} />}</details>
      </section></>:null}
      {data.features && <details open><summary>Available current model features</summary><p>These current features may be newer than the saved prediction.</p><FeatureList snapshot={data.features} /></details>}
      <PrototypePrediction studentId={studentId} />
    </>}
  </Card>;
}
function PrototypePrediction({studentId}) {
  const [result,setResult]=useState(null),[busy,setBusy]=useState(false),[error,setError]=useState("");
  useEffect(()=>{setResult(null);setError("");setBusy(false);},[studentId]);
  const successfulProbability=Number(result?.probabilities?.Successful);
  async function evaluate(){setBusy(true);setError("");setResult(null);try{setResult(await api.predictBinaryPrototype(studentId));}catch(cause){setError(cause.message||"Prototype prediction could not be calculated.");}finally{setBusy(false);}}
  return <section className="binary-prototype-panel" aria-label="Exploratory binary Random Forest prototype">
    <h3>Experimental two-class model</h3>
    <p>This separate prototype uses 10 features from saved records. It was trained on 14 historical students with unverified labels and is not validated for student decisions. It predicts a general outcome, not a subject-specific practice need.</p>
    <Button type="button" variant="secondary" disabled={busy} onClick={evaluate}>{busy?"Evaluating prototype…":"Evaluate experimental model"}</Button>
    {error&&<p role="alert">{error}</p>}
    {result&&<div role="status"><h4>Prototype prediction: {result.predictedClass}</h4>
      {Object.entries(result.probabilities||{}).map(([label,value])=><p key={label}>{label}: <strong>{(value*100).toFixed(1)}%</strong> class probability</p>)}
      {Number.isFinite(successfulProbability) && <p><strong>Personalized pathway guidance:</strong> {successfulProbability>=PROTOTYPE_SUCCESS_THRESHOLD?"Ready to next learning path":"Practice recommended before the next learning path"} ({(successfulProbability*100).toFixed(1)}% Successful class probability; the pathway threshold is {PROTOTYPE_SUCCESS_THRESHOLD*100}% or higher).</p>}
      <p className="teacher-note">Exploratory only · {result.featureCount} features · {result.modelVersion} · {result.predictedAt?new Date(result.predictedAt).toLocaleString():"timestamp unavailable"}</p>
      <p>{result.warning}</p><details><summary>Features and source records used</summary><FeatureList snapshot={{features:result.features}}/><p className="teacher-note">{(result.sourceRecordIds||[]).join(" · ")}</p></details>
      <p>The prototype does not create or assign a pathway; its readiness guidance appears in Personalized learning pathways.</p>
    </div>}
  </section>;
}
export function OutcomeDecision({prediction}){return <section aria-label="Score-based application outcome"><h3>Final application outcome: {prediction.applicationOutcome === "INSUFFICIENT_DATA" ? "INSUFFICIENT_DATA" : prediction.applicationOutcome}</h3><p>The score-based outcome is calculated separately from the Random Forest prediction; a disagreement does not change the raw model result.</p>{Number.isFinite(prediction.verifiedPerformancePercentage) && <p>Verified assessment performance: <strong>{prediction.verifiedPerformancePercentage.toFixed(2)}%</strong> · {prediction.verifiedAssessmentCount ?? "?"} valid completed assessment(s) · 80% or above is Successful.</p>}{prediction.applicationOutcome === "INSUFFICIENT_DATA" && <p>The final outcome is withheld until the required live model features and valid completed assessment evidence are available.</p>}{prediction.discrepancy && <p className="teacher-note">The Random Forest result and score rule differ: {prediction.discrepancy}</p>}{prediction.applicationOutcome === "Successful" && <><p>No new remedial pathway was generated.</p>{prediction.pathwayStatus === "ERROR" && prediction.pathwayMessage && <p className="teacher-note">The score outcome is valid; the evaluation service could not finish the model/pathway refresh: {prediction.pathwayMessage}. The saved Random Forest result was retained.</p>}</>}{prediction.applicationOutcome === "Needs Practice" && prediction.pathwayMessage && <p className="teacher-note">Pathway status ({prediction.pathwayStatus || "not recorded"}): {prediction.pathwayMessage}</p>}{prediction.assessmentEvidence?.length>0 && <details open><summary>Verified assessment evidence used ({prediction.assessmentEvidence.length})</summary><ul>{prediction.assessmentEvidence.map(row=><li key={row.assessmentResultId}>{row.title || row.assessmentIdentifier || "Assessment"}: {row.score} / {row.maximumScore} ({row.percentage.toFixed(2)}%) · {row.assessmentType || row.sourceComponent || row.completionStatus}{row.assessedAt?` · ${new Date(row.assessedAt).toLocaleDateString()}`:""}</li>)}</ul></details>}</section>;}
function FeatureList({snapshot}){return <dl className="milestone-features">{Object.entries(snapshot.features || {}).map(([key,value])=><React.Fragment key={key}><dt>{key}</dt><dd>{String(value)}</dd></React.Fragment>)}</dl>;}
