import ClassroomLessons from "../features/dashboard/components/ClassroomLessons";
import React from "react";
import { Button } from "../components";
import SummaryCard from "../features/dashboard/components/SummaryCard";
import PrototypePracticeAlerts from "../features/recommendations/components/PrototypePracticeAlerts";

export default function DashboardPage({ classroom, contentItems = [], onAssignMaterial, onCreatePracticeModule, sessionUser, activeId, students, activeStudent, recommendations, saving, busy, error, setActiveId, setShowMaterialForm, handleGenerate, setError, navigate }) {
  const metric = (key, suffix = "") => {
    if (classroom?.loading) return "…";
    if (classroom?.error || classroom?.statisticsError) return "—";
    const stats = classroom?.statistics || {};
    const value = key === "classroomLessons" ? stats.classroomLessons ?? ((stats.completedLessons || 0) + (stats.remainingLessons || 0)) : stats[key] ?? 0;
    return `${key === "learningProgress" ? Number(value.toFixed(2)) : value}${suffix}`;
  };
  const pendingCount = recommendations.filter((item) => item.status === "PENDING").length;
  const greeting = new Intl.DateTimeFormat("en", { weekday: "long", month: "long", day: "numeric" }).format(new Date());
  const greetingHour = new Date().getHours();
  const greetingText = greetingHour < 12 ? "Good morning" : greetingHour < 17 ? "Good afternoon" : "Good evening";
  return (
    <>
      <section className="teacher-dashboard-heading" id="overview">
        <div>
          <div className="dashboard-eyebrow"><span /> CLASSROOM OVERVIEW <i>·</i> {greeting}</div>
          <h1>{greetingText}, {sessionUser.name}!</h1>
          <p>Your learning dashboard is ready. Here’s what’s happening in your classroom.</p>
        </div>
        <div className="teacher-dashboard-tools">
          <label className="dashboard-grade-select">
            <span className="sr-only">Select teacher or student</span>
            <span aria-hidden="true">♧</span>
            <select value={activeId} onChange={(event) => setActiveId(event.target.value)}>
              <option value="">Teacher</option>
              {students.map((student) => <option key={student.id} value={student.id}>{student.grade} · {student.name}</option>)}
            </select>
          </label>
          <Button variant="secondary" onClick={() => setShowMaterialForm(true)}>＋ Add learning material</Button>
          <Button onClick={handleGenerate} disabled={saving || !activeStudent}>{saving ? "Preparing…" : "✧ Create pathway"}</Button>
        </div>
      </section>

      {error && (
        <div className="alert" role="alert">
          <span>{error}</span>
          <Button variant="quiet" type="button" onClick={() => setError("")} aria-label="Dismiss error">×</Button>
        </div>
      )}
      {busy && students.length === 0 && <div className="loading-state">Connecting to your learning workspace…</div>}

      <section className="dashboard-metrics" aria-label="Classroom summary" aria-busy={Boolean(classroom?.loading)}>
        <SummaryCard label="Students enrolled" value={metric("studentsEnrolled")} icon="♧" tone="violet" foot="Active students in your classroom" />
        <SummaryCard label="Learning records" value={metric("learningRecords")} icon="▤" tone="blue" foot="All uploaded learning materials" href="/dashboard/content" onNavigate={() => navigate("/dashboard/content")} />
        <SummaryCard label="Learning progress" value={metric("learningProgress", "%")} icon="↗" tone="mint" foot={`${metric("completedLessons")} of ${metric("classroomLessons")} assigned lessons completed`} />
        <SummaryCard label="Lessons remaining" value={metric("remainingLessons")} icon="!" tone="rose" foot="Assigned classroom lessons still to complete" />
      </section>
      {(classroom?.error || classroom?.statisticsError) && <div className="alert" role="alert">{classroom.error || classroom.statisticsError}<Button variant="secondary" onClick={classroom.refresh}>Retry statistics</Button></div>}

      <section className="dashboard-columns">
        <div className="dashboard-main-column">
          <ClassroomLessons onOpenLesson={(id) => navigate(`/dashboard/lessons/${encodeURIComponent(id)}`)} state={classroom} availableMaterials={contentItems.filter(item => !item.assignmentMode || item.assignmentMode === "LIBRARY")} onAssignMaterial={onAssignMaterial} />

        </div>

        <aside className="dashboard-side-column">
          <PrototypePracticeAlerts students={students} onCreatePracticeModule={onCreatePracticeModule} />
        </aside>
      </section>

      <section className="dashboard-shortcuts" aria-labelledby="dashboard-shortcuts-title">
        <div className="dashboard-section-head">
          <div><h2 id="dashboard-shortcuts-title">Immediate classroom actions</h2><p>Quick links to your most-used teacher tools.</p></div>
        </div>
        <div className="dashboard-shortcut-grid">
          <button type="button" className="dashboard-shortcut" onClick={() => navigate("/dashboard/profile")}>
            <span className="shortcut-icon blue">♧</span><span className="shortcut-arrow">↗</span>
            <strong>Student profiles</strong><small>Manage learners and view their learning details.</small>
            <span className="shortcut-meta">{students.length} {students.length === 1 ? "learner" : "learners"} enrolled</span>
          </button>
          <button type="button" className="dashboard-shortcut" onClick={() => setShowMaterialForm(true)}>
            <span className="shortcut-icon green">＋</span><span className="shortcut-arrow">↗</span>
            <strong>Add learning material</strong><small>Create and upload a resource for your teacher library.</small>
            <span className="shortcut-meta">Your private teacher library</span>
          </button>
          <button type="button" className="dashboard-shortcut" onClick={handleGenerate} disabled={saving || !activeStudent}>
            <span className="shortcut-icon purple">✧</span><span className="shortcut-arrow">↗</span>
            <strong>Generate activity</strong><small>Create a personalized pathway from learning progress.</small>
            <span className="shortcut-meta">Teacher review required</span>
          </button>
          <button type="button" className="dashboard-shortcut" onClick={() => navigate("/dashboard/content")}>
            <span className="shortcut-icon amber">↥</span><span className="shortcut-arrow">↗</span>
            <strong>Upload &amp; Content Management</strong><small>Upload, organize, download, and remove classroom resources.</small>
            <span className="shortcut-meta">PDF and image resources · up to 20 MB</span>
          </button>
          <a className="dashboard-shortcut" href="#pathways">
            <span className="shortcut-icon blue">✓</span><span className="shortcut-arrow">↗</span>
            <strong>Weekly review</strong><small>Review and manage pending pathway suggestions.</small>
            <span className="shortcut-meta">{pendingCount} awaiting review</span>
          </a>
        </div>
      </section>

      <footer className="page-footer">SMARTPLAY <span>·</span> Teacher-centered learning, one learner at a time.</footer>
    </>
  );
}
