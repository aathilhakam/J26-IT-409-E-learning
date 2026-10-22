import MilestoneProgress from "../features/recommendations/components/MilestoneProgress";
import StudentPathways from "../features/recommendations/components/StudentPathways";
import SubmittedFeedback from "../features/feedback/SubmittedFeedback";
import LearningEvidence from "../features/evidence/LearningEvidence";
import AssignedMaterials from "../features/content/components/AssignedMaterials";
import StudentImportTools from "../features/students/components/StudentImportTools";
import ArchiveStudentDialog from "../features/students/components/ArchiveStudentDialog";
import StudentRoster from "../features/students/components/StudentRoster";
import React, { useEffect, useMemo, useState } from "react";
import { Avatar, Badge, Button, Card, Field } from "../components";
import { displayDate, initials } from "../utils/format";
import AIRecommendationPanel from "../features/recommendations/components/AIRecommendationPanel";
import { api } from "../services/api";

function StudentProfilePage({
  onRefreshEvidence,
  contentItems,
  contentBusy,
  onDownloadMaterial,
  activeStudent,
  dataBusy,
  recommendations,
  students,
  onAddMaterial,
  onAddStudent,
  onDownloadTemplate,
  onExportStudentDetails,
  onImportExcel,
  importingStudents,
  importNotice,
  onSelectStudent,
  onArchiveStudent,
}) {
  const [selectedStudentId, setSelectedStudentId] = useState("");
  const [showArchiveForm, setShowArchiveForm] = useState(false);
  const [archiveFullName, setArchiveFullName] = useState("");
  const [archiveBusy, setArchiveBusy] = useState(false);
  const [archiveError, setArchiveError] = useState("");
  const [worksheetScores, setWorksheetScores] = useState([]);
  const [feedbackLessons, setFeedbackLessons] = useState([]);
  const [worksheetDataBusy, setWorksheetDataBusy] = useState(false);
  const [worksheetDataError, setWorksheetDataError] = useState("");
  const importTools = <StudentImportTools {...{ onImportExcel, onDownloadTemplate, onExportStudentDetails, importingStudents, onAddStudent }} studentCount={students.length} />;

  useEffect(() => {
    if (!activeStudent?.id) return undefined;
    let current = true;
    setWorksheetScores([]);
    setFeedbackLessons([]);
    setWorksheetDataBusy(true);
    setWorksheetDataError("");
    Promise.all([api.assessments(activeStudent.id), api.studentFeedback(activeStudent.id)])
      .then(([assessments, feedback]) => {
        if (!current) return;
        setWorksheetScores((assessments || []).filter(row => row.worksheet));
        const groups = new Map();
        (feedback || []).filter(row => row.submissionStatus === "SUBMITTED" && row.submittedAt).forEach(row => {
          const key = row.materialId || row.lessonTitle || "unknown-lesson";
          if (!groups.has(key)) groups.set(key, { id: key, title: row.lessonTitle || "Untitled lesson", count: 0, latest: row.submittedAt });
          const group = groups.get(key);
          group.count += 1;
          if (new Date(row.submittedAt) > new Date(group.latest)) group.latest = row.submittedAt;
        });
        setFeedbackLessons([...groups.values()]);
      })
      .catch(cause => {
        if (current) setWorksheetDataError(cause.message || "Could not load worksheet scores and lesson feedback.");
      })
      .finally(() => { if (current) setWorksheetDataBusy(false); });
    return () => { current = false; };
  }, [activeStudent?.id]);

  const worksheetProgress = useMemo(() => {
    const validScores = worksheetScores.filter(row => Number(row.maxScore) > 0);
    const totalPossible = validScores.reduce((sum, row) => sum + Number(row.maxScore), 0);
    return totalPossible ? validScores.reduce((sum, row) => sum + Number(row.score || 0), 0) / totalPossible * 100 : 0;
  }, [worksheetScores]);

  async function handleArchiveSubmit(event) {
    event.preventDefault();
    if (!activeStudent || !archiveFullName.trim()) {
      setArchiveError("Enter the student's full name to confirm.");
      return;
    }
    setArchiveBusy(true);
    setArchiveError("");
    try {
      await onArchiveStudent(activeStudent.id, {
        studentFullName: archiveFullName.trim(),
      });
      setShowArchiveForm(false);
      setSelectedStudentId("");
      setArchiveFullName("");
    } catch (cause) {
      setArchiveError(cause instanceof Error ? cause.message : "Could not archive this student.");
    } finally {
      setArchiveBusy(false);
    }
  }

  const archiveDialog = showArchiveForm && activeStudent ? (
    <ArchiveStudentDialog {...{ activeStudent, archiveBusy, archiveFullName, archiveError, setArchiveFullName, setArchiveError, setShowArchiveForm, handleArchiveSubmit }} />
  ) : null;

  if (!activeStudent && students.length === 0) {
    return (
      <section className="student-profile-empty">
        <span className="student-profile-empty-icon" aria-hidden="true">♧</span>
        <p className="dashboard-section-kicker">STUDENT PROFILE</p>
        <h1>No learner selected</h1>
        <p>Add a learner to your roster to create and view their learning profile.</p>
        {importTools}
        {importNotice && <p role="status">{importNotice}</p>}
      </section>
    );
  }

  if (!selectedStudentId || !activeStudent) {
    return (
      <section className="student-roster-page">
        <header className="student-roster-heading">
          <div>
            <div className="dashboard-eyebrow"><span /> STUDENT PROFILES <i>·</i> CLASSROOM</div>
            <h1>Your students</h1>
            <p>Select a student to view their learning progress, classroom records, and recommendations.</p>
          </div>
          {importTools}
        </header>
        {importNotice && <div className="alert" role="status">{importNotice}</div>}
        <div className="student-roster-summary">
          <span className="student-roster-summary-icon" aria-hidden="true">♧</span>
          <div><strong>{students.length} {students.length === 1 ? "student" : "students"}</strong><span>in your classroom</span></div>
        </div>
        <StudentRoster {...{ students, setSelectedStudentId, onSelectStudent }} />
      </section>
    );
  }

  return (
    <div className="student-profile-page">
      <header className="student-profile-heading">
        <div>
          <button className="student-profile-back" type="button" onClick={() => setSelectedStudentId("")}>← All students</button>
          <div className="dashboard-eyebrow"><span /> STUDENT PROFILE <i>·</i> LEARNER OVERVIEW</div>
          <h1>{activeStudent.name}</h1>
          <p>A complete view of this learner’s progress, strengths, and recent classroom activity.</p>
        </div>
        <div className="student-profile-actions">
          <Button onClick={onAddMaterial}>＋ Add learning material</Button>
          <Button
            variant="reject"
            onClick={() => {
              setArchiveFullName("");
              setArchiveError("");
              setShowArchiveForm(true);
            }}
          >Remove student</Button>
        </div>
      </header>
      {archiveDialog}

      <section className="student-profile-summary" aria-label={`${activeStudent.name} summary`} aria-busy={dataBusy || worksheetDataBusy}>
        <div className="student-profile-identity">
          <Avatar label={initials(activeStudent.name)} className="student-profile-avatar" />
          <div><strong>{activeStudent.name}</strong><span>{activeStudent.grade} learner</span>{activeStudent.createdAt && <small>Added {displayDate(activeStudent.createdAt)}</small>}</div>
        </div>
        <div className="student-profile-summary-stat"><strong>{worksheetDataBusy ? "—" : `${worksheetProgress.toFixed(0)}%`}</strong><span>Overall progress</span></div>
        <div className="student-profile-summary-stat"><strong>{worksheetDataBusy ? "—" : feedbackLessons.length}</strong><span>Lessons with feedback</span></div>
        <div className="student-profile-summary-stat"><strong>{worksheetDataBusy ? "—" : worksheetScores.length}</strong><span>Learning records</span></div>
      </section>

      <div className="student-profile-grid">
        <Card as="section" className="student-profile-card">
          <div className="student-profile-card-heading">
            <div><span className="dashboard-section-kicker">LEARNING PROFILE</span><h2>Lessons with teacher feedback</h2><p>Count of lessons where the teacher has submitted feedback.</p></div>
            <span className="student-profile-overall">{worksheetDataBusy ? "—" : feedbackLessons.length}</span>
          </div>
          <div className="student-profile-areas">
            {worksheetDataError && <p role="alert">{worksheetDataError}</p>}
            {worksheetDataBusy
              ? <div className="student-profile-blank"><span>…</span><div><strong>Loading lesson feedback</strong><p>Fetching this student’s submitted lesson feedback.</p></div></div>
              : feedbackLessons.length > 0
              ? feedbackLessons.map(lesson => <article className="student-profile-record" key={lesson.id}><span className="dashboard-activity-icon record" aria-hidden="true">✓</span><div><strong>{lesson.title}</strong><p>{lesson.count} {lesson.count === 1 ? "teacher feedback submission" : "teacher feedback submissions"}</p><small>Latest: {displayDate(lesson.latest)}</small></div><span className="student-profile-record-score">{lesson.count}</span></article>)
              : <div className="student-profile-blank"><span>▤</span><div><strong>No lesson feedback yet</strong><p>Submitted teacher feedback will be counted here by lesson.</p></div></div>}
          </div>
        </Card>

        <Card as="section" className="student-profile-card student-profile-records">
          <div className="student-profile-card-heading">
            <div><span className="dashboard-section-kicker">CLASSROOM ACTIVITY</span><h2>Worksheet score records</h2><p>Scores recorded from this student’s worksheets.</p></div>
          </div>
          {worksheetDataError && <p role="alert">{worksheetDataError}</p>}
          {worksheetDataBusy ? (
            <div className="student-profile-blank"><span>…</span><div><strong>Loading records</strong><p>Fetching classroom activity for this student.</p></div></div>
          ) : worksheetScores.length > 0 ? (
            <div className="student-profile-record-list">
              {worksheetScores.map((record) => (
                <article className="student-profile-record" key={record.id}>
                  <span className="dashboard-activity-icon record" aria-hidden="true">▤</span>
                  <div><strong>{record.worksheetTitle || "Worksheet assessment"}</strong><p>{record.subject || "Subject not specified"}{record.notes ? ` · ${record.notes}` : ""}</p><small>{displayDate(record.assessmentDate || record.assessedAt)}</small></div>
                  <span className="student-profile-record-score">{record.score} / {record.maxScore} · {Number(record.percentage || 0).toFixed(0)}%</span>
                </article>
              ))}
            </div>
          ) : (
            <div className="student-profile-blank">
              <span>＋</span>
              <div><strong>No worksheet scores yet</strong><p>Imported worksheet scores will appear here.</p></div>
            </div>
          )}
        </Card>
      </div>

      <SubmittedFeedback key={`feedback-${activeStudent.id}`} studentId={activeStudent.id} />

      <LearningEvidence key={activeStudent.id} student={activeStudent} onRefresh={onRefreshEvidence} />

      <AssignedMaterials student={activeStudent} items={contentItems} busy={contentBusy} onDownload={onDownloadMaterial} />

      <MilestoneProgress showRecommendations={false} key={`milestones-${activeStudent.id}`} studentId={activeStudent.id} materials={contentItems} onChanged={()=>onRefreshEvidence?.(activeStudent.id)} />
      <AIRecommendationPanel studentId={activeStudent.id} />

      <StudentPathways key={`pathways-${activeStudent.id}`} student={activeStudent} materials={contentItems} initial={recommendations} onChanged={onRefreshEvidence} />

    </div>
  );
}

export default StudentProfilePage;
