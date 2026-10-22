import React,{useState} from "react";
import {Button} from "../../components";
import {api} from "../../services/api";
export function openOriginalForPrint(blob,title,popup){
 const url=URL.createObjectURL(blob);popup.document.title=title;
 popup.addEventListener("load",()=>{try{popup.focus();popup.print();}catch{/* Native PDF viewer retains its own Print action. */}},{once:true});
 popup.location.href=url;setTimeout(()=>URL.revokeObjectURL(url),300000);
}
export default function WorksheetPrintActions({materialId,title,onView}){
 const [busy,setBusy]=useState(false),[error,setError]=useState("");
 async function print(){if(busy)return;const popup=window.open("about:blank","_blank");if(!popup){setError("Allow a new tab to open the original worksheet for printing.");return;}popup.opener=null;setBusy(true);setError("");try{const blob=await api.downloadContent(materialId);openOriginalForPrint(blob,title,popup);}catch(e){popup.close();setError(e.message);}finally{setBusy(false);}}
 return <div className="worksheet-print-actions"><Button variant="secondary" onClick={onView}>View Worksheet</Button><Button variant="secondary" disabled={busy} onClick={print}>{busy?"Opening…":"Print Worksheet"}</Button>{error && <p role="alert">{error}</p>}</div>;
}
