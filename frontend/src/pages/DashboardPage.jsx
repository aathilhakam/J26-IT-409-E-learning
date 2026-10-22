import MilestoneProgress from "../features/recommendations/components/MilestoneProgress";
import ClassroomLessons from "../features/dashboard/components/ClassroomLessons";
import LivePredictionState from "../features/recommendations/components/LivePredictionState";
import React from "react";
import { Button, Card } from "../components";
import { displayDate } from "../utils/format";
import SummaryCard from "../features/dashboard/components/SummaryCard";
import RecommendationCard from "../features/recommendations/components/RecommendationCard";

export default function DashboardPage({ classroom, livePrediction, contentItems = [], handleAssignRecommendation, onAssignMaterial, sessionUser, activeId, students, activeStudent, records, profile, average, areas, recommendations, saving, busy, error, editingId, setActiveId, setShowMaterialForm, setShowStudentForm, handleGenerate, setError, setEditingId, handleReview, navigate }) {
  const metric = (key, suffix = "") => {
    if (classroom?.loading) return "…";
    if (classroom?.error || classroom?.statisticsError) return "—";
    const stats = classroom?.statistics || {};
    const value = key === "classroomLessons" ? stats.classroomLessons ?? ((stats.completedLessons || 0) + (stats.remainingLessons || 0)) : stats[key] ?? 0;
    return `${key === "learningProgress" ? Number(value.toFixed(2)) : value}${suffix}`;
  };
  const pendingCount = recommendations.filter((item) => item.status === "PENDING").length;
  const pathwayRows = classroom?.pathways || [];
  const personalizedRows = pathwayRows.filter(row => row.recommendation.pathwayType === "PERSONALIZED"
    || (!row.recommendation.pathwayType && row.recommendation.source === "RANDOM_FOREST" && row.recommendation.predictedOutcome === "Needs Practice"));
  const commonRows = pathwayRows.filter(row => !personalizedRows.includes(row));
  const greeting = new Intl.DateTimeFormat("en", { weekday: "long", month: "long", day: "numeric" }).format(new Date());
  const greetingHour = new Date().getHours();
  const greetingText = greetingHour < 12 ? "Good morning" : greetingHour < 17 ? "Good afternoon" : "Good evening";
  const growthArea = [...areas].sort((first, second) => first.averagePercentage - second.averagePercentage)[0];
  const strongestArea = [...areas].sort((first, second) => second.averagePercentage - first.averagePercentage)[0];
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

          <MilestoneProgress key={activeStudent?.id || "teacher"} studentId={activeStudent?.id} materials={contentItems} onChanged={classroom?.refresh} />
          <section className="dashboard-pathways" id="pathways">
            <div className="dashboard-section-head pathways-head">
              <div>
                <span className="dashboard-section-kicker purple">✧ TEACHER-REVIEWED</span>
                <h2>Learning pathways for every student</h2>
                <p>Random Forest pathways are generated after each student completes three lessons. Students predicted as “Needs Practice” receive personalized practice; other students follow the common pathway.</p>
              </div>
              <Button variant="secondary" onClick={handleGenerate} disabled={saving || !activeStudent}>
                {saving ? "Preparing…" : "＋ Create pathway"}
              </Button>
            </div>
            <LivePredictionState state={livePrediction} />
            {[
              ["Personalized practice pathways · Needs Practice", personalizedRows],
              ["Common learning pathways · Other predictions", commonRows],
            ].map(([title, rows]) => <section className="teacher-pathway-group" key={title}>
              <h3>{title}</h3>
              {rows.length ? <div className="recommendation-list">{rows.map(({ studentId, studentName, studentGrade, recommendation }) => <article className="teacher-pathway-row" key={recommendation.id}>
                <p className="dashboard-activity-chip">{studentName} · {studentGrade}</p>
                <RecommendationCard
                  recommendation={recommendation}
                  materials={contentItems}
                  onAssign={(materialId) => handleAssignRecommendation(recommendation, materialId)}
                  saving={saving}
                  editing={editingId === recommendation.id}
                  onEdit={() => setEditingId(recommendation.id)}
                  onCancelEdit={() => setEditingId("")}
                  onReview={(status, data) => void handleReview(recommendation, status, data)}
                />
              </article>)}</div> : <p className="teacher-note">No saved pathways in this group yet.</p>}
            </section>)}
          </section>
        </div>

        <aside className="dashboard-side-column">
          <Card as="section" className="dashboard-progress-panel" id="snapshot">
            <div className="dashboard-section-head">
              <div>
                <span className="dashboard-section-kicker">LEARNER PROFILE</span>
                <h2>{activeStudent ? activeStudent.name : "Learning progress"}</h2>
                <p>{activeStudent ? `${activeStudent.grade} · ${profile?.assessmentCount ?? 0} records` : "Select a learner to view progress."}</p>
              </div>
              {activeStudent && <span className="dashboard-profile-percent">{average.toFixed(0)}%</span>}
            </div>
            {activeStudent ? (
              <>
                <div className="dashboard-progress-list">
                  {areas.length > 0 ? areas.slice(0, 5).map((area) => (
                    <div className="dashboard-progress-row" key={area.domain}>
                      <div><strong>{area.domain}</strong><span>{area.averagePercentage.toFixed(0)}%</span></div>
                      <div className="dashboard-progress-track"><span style={{ width: `${Math.min(100, area.averagePercentage)}%` }} /></div>
                    </div>
                  )) : <p className="dashboard-no-progress">Learning-area progress will appear here after you add records.</p>}
                </div>
                {strongestArea && <div className="dashboard-highlight"><span className="green-dot" /> Strongest area <strong>{strongestArea.domain} · {strongestArea.averagePercentage.toFixed(0)}%</strong></div>}
                {growthArea && (
                  <div className="dashboard-insight">
                    <span aria-hidden="true">✧</span>
                    <div><strong>Area to nurture</strong><p>{growthArea.domain} is a good place to focus the next learning activity.</p></div>
                  </div>
                )}
              </>
            ) : (
              <div className="dashboard-no-progress">Add a learner to view their learning areas and progress.</div>
            )}
          </Card>

          <Card as="section" className="dashboard-prompt-panel">
            <span className="dashboard-prompt-icon">✧</span>
            <div>
              <span className="dashboard-section-kicker purple">NEXT CLASSROOM STEP</span>
              <h2>{growthArea ? `Explore ${growthArea.domain}` : "Build a learning journey"}</h2>
              <p>{growthArea ? `Create a teacher-reviewed activity to support ${activeStudent?.name ?? "your learner"} in this area.` : "Add a learning record to get personalized pathway ideas for your learner."}</p>
              <Button onClick={handleGenerate} disabled={saving || !activeStudent}>{saving ? "Preparing…" : "Create pathway"} <span aria-hidden="true">→</span></Button>
            </div>
          </Card>
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
