import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../services/api";
import { Avatar, Badge, Button, Card, EmptyState, Field, SectionHeading, WorkspaceLayout } from "../components";
import { AuthPage } from "../features/auth/AuthPage";

const initialProfile = null;

function initials(name) {
  return name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

function displayDate(value) {
  return new Intl.DateTimeFormat("en", { month: "short", day: "numeric" }).format(
    new Date(value),
  );
}

function App() {
  const [pathname, setPathname] = useState(() => {
    const currentPath = window.location.pathname;
    return currentPath === "/login" || currentPath === "/register" || currentPath === "/dashboard" || currentPath === "/dashboard/profile"
      || currentPath === "/dashboard/content"
      ? currentPath
      : "/login";
  });
  const [sessionUser, setSessionUser] = useState(null);
  const [sessionChecked, setSessionChecked] = useState(false);
  const [students, setStudents] = useState([]);
  const [activeId, setActiveId] = useState("");
  const [profile, setProfile] = useState(initialProfile);
  const [records, setRecords] = useState([]);
  const [recommendations, setRecommendations] = useState([]);
  const [learnerDataBusy, setLearnerDataBusy] = useState(false);
  const learnerDataRequestId = useRef(0);
  const [contentItems, setContentItems] = useState([]);
  const [contentBusy, setContentBusy] = useState(false);
  const [contentSaving, setContentSaving] = useState(false);
  const [busy, setBusy] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [showStudentForm, setShowStudentForm] = useState(false);
  const [showRecordForm, setShowRecordForm] = useState(false);
  const [editingId, setEditingId] = useState("");
  const [pendingDashboardSection, setPendingDashboardSection] = useState("");
  const isProtectedPath = pathname === "/dashboard" || pathname === "/dashboard/profile" || pathname === "/dashboard/content";
  const isDashboardPath = pathname === "/dashboard" || pathname === "/dashboard/profile";

  useEffect(() => {
    if (window.location.pathname !== pathname) {
      window.history.replaceState({}, "", pathname);
    }
    const handlePopState = () => {
      const currentPath = window.location.pathname;
      setPathname(currentPath === "/login" || currentPath === "/register" || currentPath === "/dashboard" || currentPath === "/dashboard/profile" || currentPath === "/dashboard/content"
        ? currentPath
        : "/login");
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, [pathname]);

  const navigate = useCallback((path) => {
    if (window.location.pathname !== path) {
      window.history.pushState({}, "", path);
    }
    setPathname(path);
  }, []);

  const handleDashboardNavigate = useCallback((page) => {
    if (page === "Weekly Review") {
      setPendingDashboardSection("pathways");
      navigate("/dashboard");
      return;
    }
    navigate(page === "Dashboard" ? "/dashboard" : page === "Student Profile" ? "/dashboard/profile" : "/dashboard/content");
  }, [navigate]);

  useEffect(() => {
    let active = true;
    if (!isProtectedPath) {
      setSessionChecked(true);
      return () => {
        active = false;
      };
    }
    setSessionChecked(false);
    api.currentUser()
      .then((user) => {
        if (!active) return;
        setSessionUser(user);
        setSessionChecked(true);
      })
      .catch(() => {
        if (!active) return;
        setSessionUser(null);
        setSessionChecked(true);
        navigate("/login");
      });
    return () => {
      active = false;
    };
  }, [isProtectedPath, navigate]);

  const handleAuthSubmit = useCallback(async (account) => {
    const user = pathname === "/register"
      ? await api.register(account)
      : await api.login(account);
    setSessionUser(user);
    navigate("/dashboard");
  }, [navigate, pathname]);

  const handleLogout = useCallback(async () => {
    setError("");
    try {
      await api.logout();
      setSessionUser(null);
      navigate("/login");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not log out.");
    }
  }, [navigate]);

  const activeStudent = useMemo(
    () => students.find((student) => student.id === activeId),
    [students, activeId],
  );

  const loadStudents = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      const data = await api.students();
      setStudents(data);
      setActiveId((current) =>
        current && data.some((student) => student.id === current)
          ? current
          : (data[0]?.id ?? ""),
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load learners.");
    } finally {
      setBusy(false);
    }
  }, []);

  const loadLearnerData = useCallback(async (studentId) => {
    const requestId = ++learnerDataRequestId.current;
    if (!studentId) {
      setProfile(null);
      setRecords([]);
      setRecommendations([]);
      setLearnerDataBusy(false);
      return;
    }
    setLearnerDataBusy(true);
    setProfile(null);
    setRecords([]);
    setRecommendations([]);
    setError("");
    try {
      const [nextProfile, nextRecords, nextRecommendations] = await Promise.all([
        api.profile(studentId),
        api.records(studentId),
        api.recommendations(studentId),
      ]);
      if (requestId !== learnerDataRequestId.current) return;
      setProfile(nextProfile);
      setRecords(nextRecords);
      setRecommendations(nextRecommendations);
    } catch (cause) {
      if (requestId !== learnerDataRequestId.current) return;
      setError(cause instanceof Error ? cause.message : "Could not load learner data.");
    } finally {
      if (requestId === learnerDataRequestId.current) setLearnerDataBusy(false);
    }
  }, []);

  useEffect(() => {
    if (!isDashboardPath || !sessionChecked || !sessionUser) return;
    void loadStudents();
  }, [isDashboardPath, loadStudents, sessionChecked, sessionUser]);

  useEffect(() => {
    if (!isDashboardPath || !sessionChecked || !sessionUser) return;
    void loadLearnerData(activeId);
  }, [activeId, isDashboardPath, loadLearnerData, sessionChecked, sessionUser]);

  const loadContentItems = useCallback(async () => {
    setContentBusy(true);
    setError("");
    try {
      setContentItems(await api.content());
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load uploaded content.");
    } finally {
      setContentBusy(false);
    }
  }, []);

  useEffect(() => {
    if (pathname === "/dashboard/content" && sessionChecked && sessionUser) {
      void loadContentItems();
    }
  }, [loadContentItems, pathname, sessionChecked, sessionUser]);

  async function handleContentUpload(file, { title, category, description }) {
    setContentSaving(true);
    setError("");
    try {
      const form = new FormData();
      form.append("title", title);
      form.append("category", category);
      form.append("description", description);
      form.append("file", file);
      const uploaded = await api.uploadContent(form);
      setContentItems((current) => [uploaded, ...current]);
      return uploaded;
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not upload content.");
      throw cause;
    } finally {
      setContentSaving(false);
    }
  }

  async function handleContentDownload(item) {
    setError("");
    try {
      const file = await api.downloadContent(item.id);
      const objectUrl = URL.createObjectURL(file);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = item.originalFileName;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not download this file.");
    }
  }

  async function handleContentDelete(item) {
    if (!window.confirm(`Delete "${item.title}"? This also removes its uploaded file.`)) return;
    setError("");
    try {
      await api.deleteContent(item.id);
      setContentItems((current) => current.filter((content) => content.id !== item.id));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not delete this content.");
    }
  }

  useEffect(() => {
    if (pathname !== "/dashboard" || !pendingDashboardSection) return;
    document.getElementById(pendingDashboardSection)?.scrollIntoView({ behavior: "smooth", block: "start" });
    setPendingDashboardSection("");
  }, [pathname, pendingDashboardSection]);

  async function handleCreateStudent(event) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError("");
    try {
      const student = await api.createStudent(
        String(form.get("name")).trim(),
        String(form.get("grade")).trim(),
      );
      setStudents((current) => [student, ...current]);
      setActiveId(student.id);
      setShowStudentForm(false);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not add learner.");
    } finally {
      setSaving(false);
    }
  }

  async function handleAddRecord(event) {
    event.preventDefault();
    if (!activeId) return;
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError("");
    try {
      await api.addRecord(activeId, {
        domain: String(form.get("domain")),
        score: Number(form.get("score")),
        maxScore: Number(form.get("maxScore")),
        notes: String(form.get("notes") ?? ""),
      });
      setShowRecordForm(false);
      await loadLearnerData(activeId);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not save assessment.");
    } finally {
      setSaving(false);
    }
  }

  async function handleGenerate() {
    if (!activeId) return;
    setSaving(true);
    setError("");
    try {
      const recommendation = await api.generateRecommendation(activeId);
      setRecommendations((current) => [recommendation, ...current]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not generate a pathway.");
    } finally {
      setSaving(false);
    }
  }

  async function handleReview(
    recommendation,
    status,
    overrides,
  ) {
    setSaving(true);
    setError("");
    try {
      const updated = await api.reviewRecommendation(recommendation.id, {
        status,
        ...overrides,
      });
      setRecommendations((current) =>
        current.map((item) => (item.id === updated.id ? updated : item)),
      );
      setEditingId("");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not update recommendation.");
    } finally {
      setSaving(false);
    }
  }

  const average = profile?.overallAverage ?? 0;
  const areas = profile?.areas ?? [];
  const pendingCount = recommendations.filter((item) => item.status === "PENDING").length;
  const greeting = new Intl.DateTimeFormat("en", { weekday: "long", month: "long", day: "numeric" }).format(new Date());
  const greetingHour = new Date().getHours();
  const greetingText = greetingHour < 12 ? "Good morning" : greetingHour < 17 ? "Good afternoon" : "Good evening";
  const growthArea = [...areas].sort((first, second) => first.averagePercentage - second.averagePercentage)[0];
  const strongestArea = [...areas].sort((first, second) => second.averagePercentage - first.averagePercentage)[0];
  if (!isProtectedPath) {
    return (
      <AuthPage
        mode={pathname === "/login" ? "login" : "register"}
        onModeChange={(mode) => navigate(mode === "login" ? "/login" : "/register")}
        onSubmit={handleAuthSubmit}
      />
    );
  }

  if (!sessionChecked || !sessionUser) {
    return <main className="auth-loading">Checking your secure session…</main>;
  }

  if (pathname === "/dashboard/content") {
    return (
      <WorkspaceLayout
        activePage="Upload & Content"
        onNavigate={handleDashboardNavigate}
        teacherInitial={initials(sessionUser.name) || "T"}
        onLogout={handleLogout}
      >
        {error && (
          <div className="alert" role="alert">
            <span>{error}</span>
            <Button variant="quiet" type="button" onClick={() => setError("")} aria-label="Dismiss error">×</Button>
          </div>
        )}
        <ContentManagementPage
          items={contentItems}
          busy={contentBusy}
          saving={contentSaving}
          onUpload={handleContentUpload}
          onDownload={handleContentDownload}
          onDelete={handleContentDelete}
        />
        <footer className="page-footer">SMARTPLAY <span>·</span> Your classroom resources, together in one place.</footer>
      </WorkspaceLayout>
    );
  }

  if (pathname === "/dashboard/profile") {
    return (
      <WorkspaceLayout
        activePage="Student Profile"
        onNavigate={handleDashboardNavigate}
        teacherInitial={initials(sessionUser.name) || "T"}
        onLogout={handleLogout}
      >
        {error && (
          <div className="alert" role="alert">
            <span>{error}</span>
            <Button variant="quiet" type="button" onClick={() => setError("")} aria-label="Dismiss error">×</Button>
          </div>
        )}
        {busy && students.length === 0
          ? <div className="loading-state">Loading your student roster…</div>
          : (
            <StudentProfilePage
              activeStudent={activeStudent}
              areas={areas}
              average={average}
              dataBusy={learnerDataBusy}
              records={records}
              recommendations={recommendations}
              students={students}
              onAddRecord={() => setShowRecordForm(true)}
              onAddStudent={() => setShowStudentForm(true)}
              onSelectStudent={setActiveId}
            />
          )}
        <footer className="page-footer">SMARTPLAY <span>·</span> A closer look at each learner’s progress.</footer>
        {showRecordForm && activeStudent && (
          <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
            if (event.target === event.currentTarget) setShowRecordForm(false);
          }}>
            <form className="modal" onSubmit={handleAddRecord} aria-label="Add learning record">
              <Button variant="quiet" className="modal-close" type="button" onClick={() => setShowRecordForm(false)} aria-label="Close">×</Button>
              <div className="eyebrow">LEARNING JOURNEY</div>
              <h2>Add a learning record</h2>
              <p>Capture a recent observation for {activeStudent.name}.</p>
              <Field label="Learning area">
                <input name="domain" list="learning-areas" placeholder="e.g. Creativity" required />
                <datalist id="learning-areas">
                  <option value="Literacy" /><option value="Numeracy" /><option value="Creativity" /><option value="Motor skills" /><option value="Critical thinking" />
                </datalist>
              </Field>
              <div className="score-fields">
                <Field label="Score"><input name="score" type="number" min="0" step="0.5" defaultValue="4" required /></Field>
                <Field label="Out of"><input name="maxScore" type="number" min="0.1" step="0.5" defaultValue="5" required /></Field>
              </div>
              <Field label="Observation" hint="Optional">
                <textarea name="notes" rows={3} placeholder="What did you notice during the activity?" />
              </Field>
              <div className="modal-actions">
                <Button variant="quiet" type="button" onClick={() => setShowRecordForm(false)}>Cancel</Button>
                <Button type="submit" disabled={saving}>{saving ? "Saving…" : "Save learning record"}</Button>
              </div>
            </form>
          </div>
        )}
        {showStudentForm && (
          <LearnerModal
            onClose={() => setShowStudentForm(false)}
            onSubmit={handleCreateStudent}
            saving={saving}
          />
        )}
      </WorkspaceLayout>
    );
  }

  return (
    <WorkspaceLayout
      activePage="Dashboard"
      onNavigate={handleDashboardNavigate}
      teacherInitial={initials(sessionUser.name) || "T"}
      onLogout={handleLogout}
    >
      <section className="teacher-dashboard-heading" id="overview">
        <div>
          <div className="dashboard-eyebrow"><span /> CLASSROOM OVERVIEW <i>·</i> {greeting}</div>
          <h1>{greetingText}, {sessionUser.name}!</h1>
          <p>Your learning dashboard is ready. Here’s what’s happening in your classroom.</p>
        </div>
        <div className="teacher-dashboard-tools">
          <label className="dashboard-grade-select">
            <span className="sr-only">Select active learner</span>
            <span aria-hidden="true">♧</span>
            <select value={activeId} onChange={(event) => setActiveId(event.target.value)} disabled={students.length === 0}>
              {students.length === 0 && <option value="">No learners yet</option>}
              {students.map((student) => <option key={student.id} value={student.id}>{student.grade} · {student.name}</option>)}
            </select>
          </label>
          <Button variant="secondary" onClick={() => setShowRecordForm(true)} disabled={!activeStudent}>＋ Add learning record</Button>
          <Button onClick={handleGenerate} disabled={saving || records.length === 0}>{saving ? "Preparing…" : "✧ Create pathway"}</Button>
        </div>
      </section>

      {error && (
        <div className="alert" role="alert">
          <span>{error}</span>
          <Button variant="quiet" type="button" onClick={() => setError("")} aria-label="Dismiss error">×</Button>
        </div>
      )}
      {busy && students.length === 0 && <div className="loading-state">Connecting to your learning workspace…</div>}

      <section className="dashboard-metrics" aria-label="Classroom summary">
        <SummaryCard label="Students enrolled" value={String(students.length).padStart(2, "0")} icon="♧" tone="violet" foot="In your classroom" />
        <SummaryCard label="Learning records" value={String(profile?.assessmentCount ?? 0).padStart(2, "0")} icon="▤" tone="blue" foot={activeStudent ? `For ${activeStudent.name}` : "Select a learner to begin"} />
        <SummaryCard label="Learning progress" value={`${average.toFixed(0)}%`} icon="↗" tone="mint" foot={areas.length > 0 ? `${areas.length} learning areas tracked` : "Build progress with a record"} />
        <SummaryCard label="Pending reviews" value={String(pendingCount)} icon="!" tone="rose" foot={pendingCount === 1 ? "Suggestion needs your review" : "Teacher-reviewed suggestions"} />
      </section>

      <section className="dashboard-columns">
        <div className="dashboard-main-column">
          <Card as="section" className="dashboard-activity-panel">
            <div className="dashboard-section-head">
              <div>
                <span className="dashboard-section-kicker"><span /> CLASSROOM ACTIVITY</span>
                <h2>Recent learning activity</h2>
                <p>{activeStudent ? `Updates for ${activeStudent.name}` : "Choose a learner to see their learning activity."}</p>
              </div>
              <span className="dashboard-live-tag">RECENT</span>
            </div>

            {pendingCount > 0 && (
              <div className="dashboard-review-alert">
                <span className="dashboard-alert-symbol">!</span>
                <div>
                  <strong>{pendingCount} {pendingCount === 1 ? "suggestion needs" : "suggestions need"} teacher review</strong>
                  <p>Review personalized pathway ideas before sharing them with a learner.</p>
                </div>
                <a href="#pathways">Review <span aria-hidden="true">→</span></a>
              </div>
            )}

            <div className="dashboard-activity-list">
              {records.slice(0, 4).map((record) => (
                <article className="dashboard-activity-item" key={record.id}>
                  <span className="dashboard-activity-icon record">▤</span>
                  <div className="dashboard-activity-copy">
                    <div><strong>{record.domain}</strong><span className="dashboard-activity-chip">LEARNING RECORD</span></div>
                    <p>{record.notes || "Learning progress recorded"}{activeStudent ? ` · ${activeStudent.name}` : ""}</p>
                    <small>{displayDate(record.assessedAt)} <i>·</i> {Math.round(record.score / record.maxScore * 100)}% score</small>
                  </div>
                  <span className="dashboard-activity-score">{Math.round(record.score / record.maxScore * 100)}%</span>
                </article>
              ))}
              {records.length === 0 && activeStudent && (
                <div className="dashboard-empty-activity">
                  <span>▤</span>
                  <div><strong>No learning records yet</strong><p>Add an observation or assessment to start building this learner’s progress profile.</p></div>
                  <Button variant="secondary" onClick={() => setShowRecordForm(true)}>Add record</Button>
                </div>
              )}
              {!activeStudent && !busy && (
                <div className="dashboard-empty-activity">
                  <span>♧</span>
                  <div><strong>Your classroom is ready</strong><p>Add your first learner to begin tracking progress and creating pathways.</p></div>
                  <Button onClick={() => setShowStudentForm(true)}>＋ Add learner</Button>
                </div>
              )}
            </div>
          </Card>

          <section className="dashboard-pathways" id="pathways">
            <div className="dashboard-section-head pathways-head">
              <div>
                <span className="dashboard-section-kicker purple">✧ TEACHER-REVIEWED</span>
                <h2>Personalized pathways</h2>
                <p>AI-generated ideas, always guided by you.</p>
              </div>
              <Button variant="secondary" onClick={handleGenerate} disabled={saving || records.length === 0}>
                {saving ? "Preparing…" : "＋ Create pathway"}
              </Button>
            </div>
            <div className="recommendation-list">
              {recommendations.slice(0, 3).map((recommendation) => (
                <RecommendationCard
                  key={recommendation.id}
                  recommendation={recommendation}
                  saving={saving}
                  editing={editingId === recommendation.id}
                  onEdit={() => setEditingId(recommendation.id)}
                  onCancelEdit={() => setEditingId("")}
                  onReview={(status, data) => void handleReview(recommendation, status, data)}
                />
              ))}
              {recommendations.length === 0 && (
                <Card className="pathway-empty">
                  <EmptyState
                    icon="✧"
                    title="No pathway suggestions yet"
                    description="Add a learning record, then create a personalized activity idea for your review."
                    action={<Button variant="quiet" className="text-button" disabled={records.length === 0 || saving} onClick={handleGenerate}>Create a suggestion <span>→</span></Button>}
                  />
                </Card>
              )}
            </div>
            {recommendations.length > 3 && <p className="teacher-note">Showing 3 of {recommendations.length} suggestions. Review each before sharing with learners.</p>}
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
              <Button onClick={handleGenerate} disabled={saving || records.length === 0}>{saving ? "Preparing…" : "Create pathway"} <span aria-hidden="true">→</span></Button>
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
          <button type="button" className="dashboard-shortcut" onClick={() => setShowRecordForm(true)} disabled={!activeStudent}>
            <span className="shortcut-icon green">＋</span><span className="shortcut-arrow">↗</span>
            <strong>Add learning record</strong><small>Capture an assessment or classroom observation.</small>
            <span className="shortcut-meta">{activeStudent ? `For ${activeStudent.name}` : "Add a learner first"}</span>
          </button>
          <button type="button" className="dashboard-shortcut" onClick={handleGenerate} disabled={saving || records.length === 0}>
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
      {showRecordForm && activeStudent && (
        <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
          if (event.target === event.currentTarget) setShowRecordForm(false);
        }}>
          <form className="modal" onSubmit={handleAddRecord} aria-label="Add learning record">
            <Button variant="quiet" className="modal-close" type="button" onClick={() => setShowRecordForm(false)} aria-label="Close">×</Button>
            <div className="eyebrow">LEARNING JOURNEY</div>
            <h2>Add a learning record</h2>
            <p>Capture a recent observation for {activeStudent.name}.</p>
            <Field label="Learning area">
              <input name="domain" list="learning-areas" placeholder="e.g. Creativity" required />
              <datalist id="learning-areas">
                <option value="Literacy" /><option value="Numeracy" /><option value="Creativity" /><option value="Motor skills" /><option value="Critical thinking" />
              </datalist>
            </Field>
            <div className="score-fields">
              <Field label="Score"><input name="score" type="number" min="0" step="0.5" defaultValue="4" required /></Field>
              <Field label="Out of"><input name="maxScore" type="number" min="0.1" step="0.5" defaultValue="5" required /></Field>
            </div>
            <Field label="Observation" hint="Optional">
              <textarea name="notes" rows={3} placeholder="What did you notice during the activity?" />
            </Field>
            <div className="modal-actions">
              <Button variant="quiet" type="button" onClick={() => setShowRecordForm(false)}>Cancel</Button>
              <Button type="submit" disabled={saving}>{saving ? "Saving…" : "Save learning record"}</Button>
            </div>
          </form>
        </div>
      )}
      {showStudentForm && (
        <LearnerModal
          onClose={() => setShowStudentForm(false)}
          onSubmit={handleCreateStudent}
          saving={saving}
        />
      )}
    </WorkspaceLayout>
  );
}

function SummaryCard({ label, value, icon, tone, foot }) {
  return (
    <Card as="article" className="summary-card">
      <div className="summary-top"><span>{label}</span><span className={`summary-icon ${tone}`}>{icon}</span></div>
      <strong className="summary-value">{value}</strong>
      <span className="summary-foot">{foot}</span>
    </Card>
  );
}

function ProgressRow({ area }) {
  const tone = area.averagePercentage >= 75 ? "green" : area.averagePercentage < 60 ? "orange" : "purple";
  return (
    <div className="progress-row">
      <div className="progress-label"><strong>{area.domain}</strong><span className={`level-tag ${tone}`}>{area.level}</span></div>
      <div className="progress-track"><span className={`progress-fill ${tone}`} style={{ width: `${Math.min(100, area.averagePercentage)}%` }} /></div>
      <div className="progress-meta"><span>{area.progress} <i>·</i> {area.assessmentCount} {area.assessmentCount === 1 ? "record" : "records"}</span><strong>{area.averagePercentage.toFixed(0)}%</strong></div>
    </div>
  );
}

function RecommendationCard({
  recommendation,
  saving,
  editing,
  onEdit,
  onCancelEdit,
  onReview,
}) {
  const [teacherFeedback, setTeacherFeedback] = useState(recommendation.teacherFeedback);
  const [decision, setDecision] = useState("APPROVED");

  function submitEdit(event) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    onReview(decision, {
      activityTitle: String(form.get("activityTitle")),
      description: String(form.get("description")),
      teacherFeedback,
    });
  }

  function beginReview(status) {
    setDecision(status);
    onEdit();
  }

  return (
    <Card as="article" className={`recommendation-card ${recommendation.status.toLowerCase()}`}>
      <div className="recommendation-symbol">✳</div>
      <div className="recommendation-body">
        <div className="recommendation-meta">
          <Badge tone="neutral" className="area-chip">{recommendation.domain}</Badge>
          <Badge tone={recommendation.status.toLowerCase()} className={`status-pill ${recommendation.status.toLowerCase()}`}>
            {recommendation.status === "PENDING" ? "Needs your review" : recommendation.status === "APPROVED" ? "Approved by you" : "Not selected"}
          </Badge>
          <span className="recommendation-date">{displayDate(recommendation.createdAt)}</span>
        </div>
        {editing ? (
          <form className="edit-recommendation" onSubmit={submitEdit}>
            <Field label="Activity title"><input name="activityTitle" defaultValue={recommendation.activityTitle} required /></Field>
            <Field label="Activity details"><textarea name="description" defaultValue={recommendation.description} rows={3} required /></Field>
            <Field label="Teacher note"><textarea value={teacherFeedback} onChange={(event) => setTeacherFeedback(event.target.value)} rows={2} placeholder="Add context for this decision (optional)" /></Field>
            <Field label="Decision">
              <select value={decision} onChange={(event) => setDecision(event.target.value === "REJECTED" ? "REJECTED" : "APPROVED")}>
                <option value="APPROVED">Approve this activity</option>
                <option value="REJECTED">Not suitable for this learner</option>
              </select>
            </Field>
            <div className="edit-actions">
              <Button variant="quiet" type="button" onClick={onCancelEdit}>Cancel</Button>
              <Button type="submit" disabled={saving}>Save teacher review</Button>
            </div>
          </form>
        ) : (
          <>
            <h3>{recommendation.activityTitle}</h3>
            <p className="recommendation-description">{recommendation.description}</p>
            <div className="reason-note"><span>Why this suggestion</span>{recommendation.reason}</div>
            {recommendation.teacherFeedback && <div className="teacher-feedback">Teacher note: {recommendation.teacherFeedback}</div>}
            {recommendation.status === "PENDING" && (
              <div className="recommendation-actions">
                <Button size="small" disabled={saving} onClick={() => onReview("APPROVED")}>Approve activity</Button>
                <Button variant="quiet" size="small" disabled={saving} onClick={() => beginReview("APPROVED")}>Edit & review</Button>
                <Button variant="reject" size="small" disabled={saving} onClick={() => beginReview("REJECTED")}>Not suitable</Button>
              </div>
            )}
          </>
        )}
      </div>
      <div className="confidence"><strong>{Math.round(recommendation.confidence * 100)}%</strong><span>match</span></div>
    </Card>
  );
}

function ContentManagementPage({ items, busy, saving, onUpload, onDownload, onDelete }) {
  const [activeTab, setActiveTab] = useState("work");
  const [queuedFiles, setQueuedFiles] = useState([]);
  const [queueError, setQueueError] = useState("");
  const [search, setSearch] = useState("");
  const [category, setCategory] = useState("All categories");
  const fileInput = useRef(null);
  const visibleItems = items.filter((item) => {
    const query = search.trim().toLowerCase();
    const matchesQuery = !query || [item.title, item.originalFileName, item.description]
      .some((value) => value?.toLowerCase().includes(query));
    return matchesQuery && (category === "All categories" || item.category === category);
  });

  function addFiles(fileList) {
    const accepted = [];
    const rejected = [];
    for (const file of Array.from(fileList ?? [])) {
      const extension = file.name.split(".").pop()?.toLowerCase();
      if (!["pdf", "jpg", "jpeg", "png", "webp"].includes(extension ?? "")) {
        rejected.push(`${file.name}: unsupported format`);
      } else if (file.size > 20 * 1024 * 1024) {
        rejected.push(`${file.name}: larger than 20 MB`);
      } else {
        accepted.push({
          key: `${file.name}-${file.size}-${file.lastModified}-${crypto.randomUUID()}`,
          file,
          title: file.name.replace(/\.[^.]+$/, "").replace(/[_-]+/g, " "),
          category: activeTab === "materials" ? "Lesson" : "Worksheet",
          description: "",
          status: "pending",
        });
      }
    }
    if (accepted.length) setQueuedFiles((current) => [...current, ...accepted]);
    setQueueError(rejected.join(". "));
  }

  function updateQueueItem(key, changes) {
    setQueuedFiles((current) => current.map((item) => item.key === key ? { ...item, ...changes } : item));
  }

  async function uploadBatch() {
    for (const item of queuedFiles) {
      if (item.status === "saved" || item.status === "uploading") continue;
      updateQueueItem(item.key, { status: "uploading" });
      try {
        await onUpload(item.file, item);
        updateQueueItem(item.key, { status: "saved" });
      } catch {
        updateQueueItem(item.key, { status: "failed" });
      }
    }
  }

  return (
    <div className="content-management-page">
      <div className="content-breadcrumb"><span>Curriculum Ingestion</span><i>›</i><strong>{activeTab === "work" ? "Student Work Assessment" : "Teacher Materials Repository"}</strong></div>
      <header className="content-management-heading">
        <div>
          <h1>Upload &amp; Content Management</h1>
          <p>Upload student work and organize curriculum resources for your classroom.</p>
        </div>
        <span className="content-engine-status"><span /> Secure storage ready</span>
      </header>

      <nav className="content-management-tabs" aria-label="Content management sections">
        <button className={activeTab === "work" ? "active" : ""} type="button" onClick={() => setActiveTab("work")}>
          <span aria-hidden="true">▧</span> Student Work Assessment <Badge tone="neutral">{queuedFiles.filter((item) => item.status !== "saved").length} queued</Badge>
        </button>
        <button className={activeTab === "materials" ? "active" : ""} type="button" onClick={() => setActiveTab("materials")}>
          <span aria-hidden="true">▤</span> Teacher Materials Repository <Badge tone="neutral">{items.length} files</Badge>
        </button>
        <span className="content-management-tab-note">Private teacher library</span>
      </nav>

      {activeTab === "work" ? (
        <>
          <div className="content-ingestion-layout">
            <Card as="section" className="content-ingestion-card">
              <div className="content-card-heading">
                <span className="content-upload-icon" aria-hidden="true">☁</span>
                <div><h2>Physical Worksheet Ingestion</h2><p>Upload student work files for secure classroom storage.</p></div>
                <Badge tone="neutral">PDF / JPG / PNG / WEBP</Badge>
              </div>
              <input
                ref={fileInput}
                className="sr-only"
                type="file"
                multiple
                accept=".pdf,.jpg,.jpeg,.png,.webp,application/pdf,image/jpeg,image/png,image/webp"
                onChange={(event) => {
                  addFiles(event.currentTarget.files);
                  event.currentTarget.value = "";
                }}
              />
              <button
                className="content-dropzone"
                type="button"
                onClick={() => fileInput.current?.click()}
                onDragOver={(event) => event.preventDefault()}
                onDrop={(event) => {
                  event.preventDefault();
                  addFiles(event.dataTransfer.files);
                }}
              >
                <span className="content-dropzone-icon" aria-hidden="true">⇧</span>
                <strong>Drop student work files here or <u>browse</u></strong>
                <small>PDF, JPG, PNG, WEBP · up to 20 MB per file</small>
                <span className="content-browse-button">▣ Select files from your device</span>
              </button>
              {queueError && <p className="content-queue-error" role="alert">{queueError}</p>}
              <div className="content-upload-capabilities"><span>● Private teacher storage</span><span>● Files remain available in your repository</span></div>
            </Card>

            <Card as="aside" className="content-pipeline-card">
              <div className="content-pipeline-label"><span>✧</span> INGESTION STATUS <Badge tone="pending">Manual review</Badge></div>
              <h2>Upload pipeline</h2>
              <p className="content-pipeline-description">Files are stored in your private library. Worksheet scanning and AI assessment are not connected yet.</p>
              <div className="content-pipeline-track"><span /></div>
              <div className="content-pipeline-stages">
                <div><span className="done">✓</span><span><strong>File validation</strong><small>Format and size checks</small></span><b>Ready</b></div>
                <div><span className="done">✓</span><span><strong>Private upload</strong><small>Teacher-owned storage</small></span><b>Ready</b></div>
                <div><span className="not-ready">3</span><span><strong>Worksheet analysis</strong><small>Requires a scanning service</small></span><b>Not connected</b></div>
              </div>
              <div className="content-pipeline-note"><span aria-hidden="true">ⓘ</span><p>Uploaded files are not automatically scored, assigned, or analyzed.</p></div>
            </Card>
          </div>

          <section className="content-batch-section">
            <div className="content-batch-heading">
              <div><h2>Current upload batch <span>({queuedFiles.length} files)</span></h2><p>Set a title and category for each file before saving it to your repository.</p></div>
              {queuedFiles.length > 0 && <Button variant="quiet" onClick={() => setQueuedFiles([])} disabled={saving}>Clear batch</Button>}
            </div>
            {queuedFiles.length > 0 ? (
              <div className="content-batch-list">
                {queuedFiles.map((item) => (
                  <article className="content-batch-item" key={item.key}>
                    <span className={`content-file-icon ${item.file.type.startsWith("image/") ? "image" : "document"}`} aria-hidden="true">
                      {item.file.type.startsWith("image/") ? "▧" : "▤"}
                    </span>
                    <div className="content-batch-fields">
                      <input aria-label={`Title for ${item.file.name}`} value={item.title} maxLength={120} onChange={(event) => updateQueueItem(item.key, { title: event.target.value })} />
                      <small>{item.file.name} · {formatContentSize(item.file.size)}</small>
                    </div>
                    <label className="sr-only" htmlFor={`category-${item.key}`}>Category for {item.file.name}</label>
                    <select id={`category-${item.key}`} value={item.category} onChange={(event) => updateQueueItem(item.key, { category: event.target.value })}>
                      <option>Lesson</option><option>Worksheet</option><option>Reading</option><option>Activity</option><option>Other</option>
                    </select>
                    <span className={`content-queue-status ${item.status}`}>{item.status === "saved" ? "Saved" : item.status === "uploading" ? "Uploading…" : item.status === "failed" ? "Failed — retry" : "Ready"}</span>
                    {item.status !== "uploading" && item.status !== "saved" && <button className="content-queue-remove" type="button" onClick={() => setQueuedFiles((current) => current.filter((entry) => entry.key !== item.key))} aria-label={`Remove ${item.file.name}`}>×</button>}
                  </article>
                ))}
              </div>
            ) : (
              <div className="content-batch-empty"><span aria-hidden="true">▤</span><strong>No files added yet</strong><p>Select or drop files above to prepare your upload batch.</p></div>
            )}
            {queuedFiles.some((item) => item.status === "pending" || item.status === "failed") && (
              <div className="content-batch-footer">
                <span><i /> {queuedFiles.filter((item) => item.status === "pending" || item.status === "failed").length} ready to upload <span>·</span> Files save to your private repository</span>
                <Button onClick={() => void uploadBatch()} disabled={saving || queuedFiles.some((item) => item.status === "uploading")}>
                  {saving ? "Uploading…" : "↥ Upload selected files"}
                </Button>
              </div>
            )}
          </section>
        </>
      ) : (
        <Card as="section" className="content-library-card">
          <div className="content-library-heading">
            <div><span className="dashboard-section-kicker">YOUR PRIVATE LIBRARY</span><h2>Teacher Materials Repository</h2><p>Browse, download, and remove your uploaded classroom resources.</p></div>
            <span className="content-library-count">{items.length}</span>
          </div>
          <div className="content-library-filters">
            <label className="content-search">
              <span aria-hidden="true">⌕</span>
              <span className="sr-only">Search resources</span>
              <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search your resources" />
            </label>
            <label className="sr-only" htmlFor="content-category-filter">Filter by category</label>
            <select id="content-category-filter" value={category} onChange={(event) => setCategory(event.target.value)}>
              <option>All categories</option><option>Lesson</option><option>Worksheet</option><option>Reading</option><option>Activity</option><option>Other</option>
            </select>
          </div>
          {busy ? (
            <div className="content-library-empty"><span>…</span><strong>Loading resources</strong><p>Getting your uploaded classroom files.</p></div>
          ) : visibleItems.length ? (
            <div className="content-library-list">
              {visibleItems.map((item) => (
                <article className="content-library-item" key={item.id}>
                  <span className={`content-file-icon ${item.contentType.startsWith("image/") ? "image" : "document"}`} aria-hidden="true">
                    {item.contentType.startsWith("image/") ? "▧" : "▤"}
                  </span>
                  <div className="content-library-item-info">
                    <div className="content-library-item-title"><strong>{item.title}</strong><Badge tone="neutral">{item.category}</Badge></div>
                    <p>{item.description || item.originalFileName}</p>
                    <small>{formatContentSize(item.size)} <i>·</i> Added {displayDate(item.uploadedAt)}</small>
                  </div>
                  <div className="content-library-item-actions">
                    <button type="button" onClick={() => void onDownload(item)} aria-label={`Download ${item.title}`}>↓</button>
                    <button type="button" onClick={() => void onDelete(item)} aria-label={`Delete ${item.title}`}>×</button>
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <div className="content-library-empty">
              <span aria-hidden="true">{items.length ? "⌕" : "▤"}</span>
              <strong>{items.length ? "No matching resources" : "Your content library is empty"}</strong>
              <p>{items.length ? "Try another search term or category." : "Upload a resource in Student Work Assessment to add it here."}</p>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}

function formatContentSize(size) {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function StudentProfilePage({
  activeStudent,
  areas,
  average,
  dataBusy,
  records,
  recommendations,
  students,
  onAddRecord,
  onAddStudent,
  onSelectStudent,
}) {
  const [selectedStudentId, setSelectedStudentId] = useState("");

  if (!activeStudent) {
    return (
      <section className="student-profile-empty">
        <span className="student-profile-empty-icon" aria-hidden="true">♧</span>
        <p className="dashboard-section-kicker">STUDENT PROFILE</p>
        <h1>No learner selected</h1>
        <p>Add a learner to your roster to create and view their learning profile.</p>
        <Button onClick={onAddStudent}>＋ Add learner</Button>
      </section>
    );
  }

  if (!selectedStudentId) {
    return (
      <section className="student-roster-page">
        <header className="student-roster-heading">
          <div>
            <div className="dashboard-eyebrow"><span /> STUDENT PROFILES <i>·</i> CLASSROOM</div>
            <h1>Your students</h1>
            <p>Select a student to view their learning progress, classroom records, and recommendations.</p>
          </div>
          <Button onClick={onAddStudent}>＋ Add student</Button>
        </header>
        <div className="student-roster-summary">
          <span className="student-roster-summary-icon" aria-hidden="true">♧</span>
          <div><strong>{students.length} {students.length === 1 ? "student" : "students"}</strong><span>in your classroom</span></div>
        </div>
        <div className="student-roster-grid">
          {students.map((student, index) => {
            const joinedDate = student.createdAt
              ? new Intl.DateTimeFormat("en", { month: "short", year: "numeric" }).format(new Date(student.createdAt))
              : "";
            return (
              <button
                className="student-roster-card"
                key={student.id}
                type="button"
                onClick={() => {
                  setSelectedStudentId(student.id);
                  onSelectStudent(student.id);
                }}
                aria-label={`View ${student.name}'s profile`}
              >
                <Avatar label={initials(student.name)} variant={index} className="student-roster-avatar" />
                <span className="student-roster-card-copy">
                  <strong>{student.name}</strong>
                  <span>{student.grade}</span>
                  {joinedDate && <small>Added {joinedDate}</small>}
                </span>
                <span className="student-roster-card-arrow" aria-hidden="true">→</span>
              </button>
            );
          })}
        </div>
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
          <Button onClick={onAddRecord}>＋ Add learning record</Button>
        </div>
      </header>

      <section className="student-profile-summary" aria-label={`${activeStudent.name} summary`} aria-busy={dataBusy}>
        <div className="student-profile-identity">
          <Avatar label={initials(activeStudent.name)} className="student-profile-avatar" />
          <div><strong>{activeStudent.name}</strong><span>{activeStudent.grade} learner</span>{activeStudent.createdAt && <small>Added {displayDate(activeStudent.createdAt)}</small>}</div>
        </div>
        <div className="student-profile-summary-stat"><strong>{dataBusy ? "—" : `${average.toFixed(0)}%`}</strong><span>Overall progress</span></div>
        <div className="student-profile-summary-stat"><strong>{dataBusy ? "—" : areas.length}</strong><span>Learning areas</span></div>
        <div className="student-profile-summary-stat"><strong>{dataBusy ? "—" : records.length}</strong><span>Learning records</span></div>
      </section>

      <div className="student-profile-grid">
        <Card as="section" className="student-profile-card">
          <div className="student-profile-card-heading">
            <div><span className="dashboard-section-kicker">LEARNING PROFILE</span><h2>Progress by learning area</h2><p>Areas tracked from this learner’s observations and assessments.</p></div>
            <span className="student-profile-overall">{average.toFixed(0)}%</span>
          </div>
          <div className="student-profile-areas">
            {dataBusy
              ? <div className="student-profile-blank"><span>…</span><div><strong>Loading progress</strong><p>Fetching this student’s learning profile.</p></div></div>
              : areas.length > 0
              ? areas.map((area) => <ProgressRow area={area} key={area.domain} />)
              : <div className="student-profile-blank"><span>▤</span><div><strong>No progress data yet</strong><p>Add a learning record to start tracking progress by area.</p></div></div>}
          </div>
        </Card>

        <Card as="section" className="student-profile-card student-profile-records">
          <div className="student-profile-card-heading">
            <div><span className="dashboard-section-kicker">CLASSROOM ACTIVITY</span><h2>Recent learning records</h2><p>Assessments and teacher observations for {activeStudent.name}.</p></div>
          </div>
          {dataBusy ? (
            <div className="student-profile-blank"><span>…</span><div><strong>Loading records</strong><p>Fetching classroom activity for this student.</p></div></div>
          ) : records.length > 0 ? (
            <div className="student-profile-record-list">
              {records.map((record) => (
                <article className="student-profile-record" key={record.id}>
                  <span className="dashboard-activity-icon record" aria-hidden="true">▤</span>
                  <div><strong>{record.domain}</strong><p>{record.notes || "Learning progress recorded"}</p><small>{displayDate(record.assessedAt)}</small></div>
                  <span className="student-profile-record-score">{Math.round(record.score / record.maxScore * 100)}%</span>
                </article>
              ))}
            </div>
          ) : (
            <div className="student-profile-blank">
              <span>＋</span>
              <div><strong>No records yet</strong><p>Add a classroom observation or assessment for this learner.</p></div>
            </div>
          )}
          <Button variant="secondary" onClick={onAddRecord}>＋ Add learning record</Button>
        </Card>
      </div>

      <Card as="section" className="student-profile-card student-profile-recommendations">
        <div className="student-profile-card-heading">
          <div><span className="dashboard-section-kicker">PERSONALIZED LEARNING</span><h2>Learning pathways</h2><p>Suggested activities created from this student’s learning records.</p></div>
        </div>
        {dataBusy ? (
          <div className="student-profile-blank"><span>…</span><div><strong>Loading pathways</strong><p>Fetching this student’s recommendations.</p></div></div>
        ) : recommendations.length ? (
          <div className="student-profile-pathway-list">
            {recommendations.map((recommendation) => (
              <article className="student-profile-pathway" key={recommendation.id}>
                <span className="student-profile-pathway-icon" aria-hidden="true">✧</span>
                <div>
                  <div className="student-profile-pathway-meta"><strong>{recommendation.activityTitle}</strong><Badge tone={recommendation.status.toLowerCase()}>{recommendation.status}</Badge></div>
                  <p>{recommendation.description}</p>
                  <small>{recommendation.domain} · {displayDate(recommendation.createdAt)}</small>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <div className="student-profile-blank"><span>✧</span><div><strong>No pathway suggestions yet</strong><p>Add learning records and create a personalized activity from the dashboard.</p></div></div>
        )}
      </Card>
    </div>
  );
}

function LearnerModal({ onClose, onSubmit, saving }) {
  return (
    <div className="modal-backdrop" role="presentation" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <form className="modal" onSubmit={onSubmit} aria-label="Add learner">
        <Button variant="quiet" className="modal-close" type="button" onClick={onClose} aria-label="Close">×</Button>
        <div className="eyebrow">YOUR CLASSROOM</div>
        <h2>Add a learner</h2>
        <p>Create a learner profile to start tracking classroom progress.</p>
        <Field label="Learner name">
          <input autoFocus name="name" placeholder="e.g. Amaya Silva" required />
        </Field>
        <Field label="Grade">
          <input name="grade" defaultValue="Grade 1" required />
        </Field>
        <div className="modal-actions">
          <Button variant="quiet" type="button" onClick={onClose}>Cancel</Button>
          <Button type="submit" disabled={saving}>{saving ? "Adding…" : "Add learner"}</Button>
        </div>
      </form>
    </div>
  );
}

export default App;
