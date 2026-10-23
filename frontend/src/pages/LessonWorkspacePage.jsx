import WorksheetPrintActions from "../features/worksheets/WorksheetPrintActions";
import React, { useEffect, useRef, useState } from "react";
import { Button, Card } from "../components";
import { api } from "../services/api";
import TeacherFeedback from "../features/feedback/TeacherFeedback";
import { refreshLessonCompletion } from "../features/dashboard/lessonFeedback";

export default function LessonWorkspacePage({ materialId, onBack }) {
  const [workspace, setWorkspace] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [preview, setPreview] = useState(null);
  const [previewError, setPreviewError] = useState("");
  const [exportBusy, setExportBusy] = useState(false);
  const [exportMessage, setExportMessage] = useState("");
  const generation = useRef(0);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; generation.current++; }; }, []);
  async function load() {
    const request = ++generation.current;
    setLoading(true); setError("");
    try { const result = await refreshLessonCompletion(api, materialId); if (mounted.current && request === generation.current) { setWorkspace(result); } }
    catch (cause) { if (mounted.current && request === generation.current) setError(cause.message || "Could not load lesson. Please retry."); }
    finally { if (mounted.current && request === generation.current) setLoading(false); }
  }
  useEffect(() => { void load(); }, [materialId]);
  useEffect(() => {
    if (!workspace) return;
    let active = true, url;
    setPreviewError("");
    api.downloadContent(materialId).then(blob => {
      if (!active) return;
      url = URL.createObjectURL(blob); setPreview({ url, type: blob.type });
    }).catch(cause => { if (active) setPreviewError(cause.message || "Preview unavailable. Try reopening this lesson."); });
    return () => { active = false; if (url) URL.revokeObjectURL(url); };
  }, [materialId, Boolean(workspace)]);
  const lesson = workspace?.lesson;
  async function downloadFeedback() {
    setExportBusy(true); setExportMessage("");
    try {
      // Refresh the authorized roster for this lesson before requesting its lesson-scoped workbook.
      const current = await api.lessonWorkspace(materialId);
      if (!current?.lesson?.recipients) throw new Error("Could not retrieve the assigned students for this lesson.");
      const result = await api.downloadLessonTeacherFeedback(materialId);
      const url = URL.createObjectURL(result.blob); const anchor = document.createElement("a");
      anchor.href = url; anchor.download = result.filename || `${lesson?.title || "Lesson"}_Teacher_Feedback.xlsx`;
      document.body.appendChild(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
      setExportMessage(result.feedbackCount === 0 ? "Excel file downloaded. No submitted teacher feedback has been saved for this lesson yet." : "Teacher feedback Excel file downloaded.");
    } catch (cause) { setExportMessage(cause.message || "Could not download teacher feedback. Please retry."); }
    finally { setExportBusy(false); }
  }
  return <div className="lesson-workspace">
    <Button variant="quiet" onClick={onBack}>← Back to dashboard</Button>
    {loading && <p role="status">Loading lesson and saved classroom feedback…</p>}
    {error && <div className="alert" role="alert">{error}{!workspace && <Button onClick={load}>Retry lesson</Button>}</div>}
    {!loading && lesson && <>
      <header className="lesson-workspace-heading"><div><span className="dashboard-section-kicker">{workspace.completed ? "Completed lesson" : workspace.weekPosition === 1 ? "Current week lesson" : workspace.weekPosition === 2 ? "Next week lesson" : "Queued lesson"}</span><h1>{lesson.title}</h1><p>{lesson.description}</p><p>{[lesson.subject, lesson.grade, lesson.materialType].filter(Boolean).join(" · ")}</p></div><div className="classroom-counts"><span>{lesson.assigned} assigned</span><span>{lesson.completed} completed</span></div></header>
      <div className="feedback-action-row"><Button variant="secondary" disabled={exportBusy} onClick={() => void downloadFeedback()}>{exportBusy ? "Preparing Excel file…" : "Download All Teacher Feedback (Excel)"}</Button></div>
      {exportMessage && <p className={exportMessage.startsWith("Could not") || exportMessage.startsWith("No ") ? "alert" : "material-success"} role={exportMessage.startsWith("Could not") ? "alert" : "status"}>{exportMessage}</p>}
      <Card as="section" className="lesson-material-preview"><h2>Learning material</h2>
        {/worksheet|activity sheet/i.test(`${lesson.materialType || ""} ${lesson.category || ""}`) && <WorksheetPrintActions materialId={materialId} title={lesson.title} onView={()=>{if(preview)window.open(preview.url,"_blank","noopener,noreferrer");}} />}
        {previewError ? <p role="alert">{previewError}</p> : !preview ? <p role="status">Loading learning material…</p> : <>
          <div className="modal-actions"><a className="text-button" href={preview.url} target="_blank" rel="noopener noreferrer">Open material in a new tab</a><a className="text-button" href={preview.url} download={lesson.title}>Download material</a></div>
          {preview.type.includes("pdf") ? <iframe title={`Learning material: ${lesson.title}`} src={preview.url} /> : preview.type.startsWith("image/") ? <img alt={lesson.title} src={preview.url} /> : <p>Use the download link to view this material.</p>}
        </>}
      </Card>
      {workspace.completed && <p role="status" className="teacher-note">Lesson complete. All saved feedback is preserved. Return to the dashboard to open the next week’s lesson.</p>}
      <TeacherFeedback key={materialId} materialId={materialId} workspace={workspace} onRefresh={async () => { const fresh = await refreshLessonCompletion(api, materialId); if (mounted.current) setWorkspace(fresh); }} />
    </>}
  </div>;
}
