import React,{useState} from "react";
import {Button,Field} from "../../components";
import {api} from "../../services/api";
export default function WorksheetCorrection({result,onSaved}){
 const [open,setOpen]=useState(false),[score,setScore]=useState(String(result.score)),[feedback,setFeedback]=useState(result.notes || ""),[reason,setReason]=useState(""),[busy,setBusy]=useState(false),[error,setError]=useState("");
 async function save(e){e.preventDefault();if(busy)return;setBusy(true);setError("");try{await api.correctWorksheetScore(result.id,{score:Number(score),feedback,reason,expectedRevision:result.worksheetRevision});setOpen(false);await onSaved();}catch(e){setError(e.message);}finally{setBusy(false);}}
 return <>{!open?<Button variant="quiet" onClick={()=>setOpen(true)}>Correct worksheet score</Button>:<form className="worksheet-score-correction" onSubmit={save}><Field label="Corrected score / 10"><input type="number" min="0" max="10" step="any" required value={score} onChange={e=>setScore(e.target.value)} /></Field><Field label="Teacher feedback"><textarea maxLength={2000} value={feedback} onChange={e=>setFeedback(e.target.value)} /></Field><Field label="Reason for correction"><textarea maxLength={1000} required value={reason} onChange={e=>setReason(e.target.value)} /></Field><p>This explicitly updates the saved score and preserves its previous value in the correction audit.</p><Button disabled={busy}>Confirm score correction</Button><Button variant="quiet" onClick={()=>setOpen(false)}>Cancel</Button></form>}{error && <p role="alert">{error}</p>}</>;
}
