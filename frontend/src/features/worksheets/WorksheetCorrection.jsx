import React,{useState} from "react";
import {Button,Field} from "../../components";
import {api} from "../../services/api";

export default function WorksheetCorrection({result,onSaved}){
 const [open,setOpen]=useState(false);
 const [score,setScore]=useState(String(result.score));
 const [feedback,setFeedback]=useState(result.notes || "");
 const [attempts,setAttempts]=useState(result.attempts == null ? "" : String(result.attempts));
 const [timeTaken,setTimeTaken]=useState(result.timeTakenMinutes == null ? "" : String(result.timeTakenMinutes));
 const [completionStatus,setCompletionStatus]=useState(result.completionStatus || "");
 const [reason,setReason]=useState("");
 const [busy,setBusy]=useState(false);
 const [error,setError]=useState("");
 const [saved,setSaved]=useState(false);
 async function save(e){
  e.preventDefault();if(busy)return;setBusy(true);setError("");
  try{
   const requestedTime=timeTaken===""?null:Number(timeTaken);
   const updated=await api.correctWorksheetScore(result.id,{score:Number(score),feedback,attempts:Number(attempts),timeTakenMinutes:requestedTime,completionStatus,reason,expectedRevision:result.worksheetRevision});
   const timeSaved=updated?.timeTakenMinutes==null?requestedTime==null:Number(updated.timeTakenMinutes)===requestedTime;
   if(updated?.attempts!==Number(attempts)||!timeSaved||updated?.completionStatus!==completionStatus){
    setError("The server did not save Attempts, Time Taken, and Status. Restart the Spring Boot backend from the updated project, then reload and try again.");
    return;
   }
   setOpen(false);await onSaved();setSaved(true);window.setTimeout(()=>setSaved(false),4000);
  }catch(e){setError(e.message);}finally{setBusy(false);}
 }
 return <>{!open?<Button variant="quiet" onClick={()=>setOpen(true)}>Update worksheet result</Button>:<form className="worksheet-score-correction" onSubmit={save}>
  <Field label={`Score / ${result.maxScore || 10}`}><input type="number" min="0" max={result.maxScore || 10} step="any" required value={score} onChange={e=>setScore(e.target.value)} /></Field>
  <Field label="Attempts"><input type="number" min="0" step="1" required value={attempts} onChange={e=>setAttempts(e.target.value)} /></Field>
  <Field label="Time Taken (Minutes)"><input type="number" min="0" step="any" value={timeTaken} onChange={e=>setTimeTaken(e.target.value)} /></Field>
  <Field label="Status"><select required value={completionStatus} onChange={e=>setCompletionStatus(e.target.value)}><option value="">Choose status</option><option value="COMPLETED">COMPLETED</option><option value="IN_PROGRESS">IN_PROGRESS</option><option value="NOT_COMPLETED">NOT_COMPLETED</option></select></Field>
  <Field label="Teacher feedback"><textarea maxLength={2000} value={feedback} onChange={e=>setFeedback(e.target.value)} /></Field>
  <Field label="Reason for update"><textarea maxLength={1000} required value={reason} onChange={e=>setReason(e.target.value)} /></Field>
  <p>This explicitly updates the saved result and preserves the previous score, feedback, attempts, time, and status in its correction audit.</p>
  <Button type="submit" disabled={busy}>{busy?"Saving…":"Save worksheet result"}</Button><Button variant="quiet" onClick={()=>setOpen(false)}>Cancel</Button>
 </form>}{error && <p role="alert">{error}</p>}{saved&&!open&&!error&&<div className="worksheet-save-toast" role="status" aria-live="polite">Worksheet result updated and saved.</div>}</>;
}
