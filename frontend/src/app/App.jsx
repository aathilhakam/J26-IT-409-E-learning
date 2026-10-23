import LessonWorkspacePage from "../pages/LessonWorkspacePage";
import { isLessonPath } from "../features/dashboard/lessonFeedback";
import useTeacherDashboard from "../features/dashboard/useTeacherDashboard";
import SuccessToast from "../components/ui/SuccessToast";
import { archiveStudent, activeStudents as filterActiveStudents } from "../features/students/archiveStudent";
import useContent from "../features/content/useContent";
import React, { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../services/api";
import { Button, WorkspaceLayout } from "../components";
import { AuthPage } from "../features/auth/AuthPage";
import DashboardPage from "../pages/DashboardPage";
import StudentProfilePage from "../pages/StudentProfilePage";
import ContentManagementPage from "../pages/ContentPage";
import LearnerModal from "../features/students/components/LearnerModal";
import MaterialEditor from "../features/content/components/MaterialEditor";
import { initials } from "../utils/format";

const initialProfile = null;

function App() {
  const [pathname, setPathname] = useState(() => {
    const currentPath = window.location.pathname;
    return currentPath === "/login" || currentPath === "/register" || currentPath === "/dashboard" || currentPath === "/dashboard/profile"
      || currentPath === "/dashboard/content" || isLessonPath(currentPath)
      ? currentPath
      : currentPath === "/dashboard/scores"
      ? "/dashboard"
      : "/login";
  });
  const [sessionUser, setSessionUser] = useState(null);
  const [sessionChecked, setSessionChecked] = useState(false);
  const [authNotice, setAuthNotice] = useState("");
  const [students, setStudents] = useState([]);
  const [activeId, setActiveId] = useState("");
  const [profile, setProfile] = useState(initialProfile);
  const [records, setRecords] = useState([]);
  const [recommendations, setRecommendations] = useState([]);
  const [livePrediction, setLivePrediction] = useState(null);
  const liveGenerationId = useRef(0);
  useEffect(() => {
    liveGenerationId.current += 1;
    setLivePrediction(null);
  }, [activeId]);
  const [learnerDataBusy, setLearnerDataBusy] = useState(false);
  const learnerDataRequestId = useRef(0);
  const [busy, setBusy] = useState(true);
  const [saving, setSaving] = useState(false);
  const [deletingRecordId, setDeletingRecordId] = useState("");
  const [error, setError] = useState("");
  const [showStudentForm, setShowStudentForm] = useState(false);
  const [importingStudents, setImportingStudents] = useState(false);
  const [importNotice, setImportNotice] = useState("");
  const [successNotice, setSuccessNotice] = useState("");
  const dismissSuccessNotice = useCallback(() => setSuccessNotice(""), []);
  const [showMaterialForm, setShowMaterialForm] = useState(false);
  const [editingId, setEditingId] = useState("");
  const [assigningMaterial, setAssigningMaterial] = useState(null);
  const [practiceModuleContext, setPracticeModuleContext] = useState(null);
  const [pendingDashboardSection, setPendingDashboardSection] = useState("");
  const isProtectedPath = isLessonPath(pathname) || pathname === "/dashboard" || pathname === "/dashboard/profile" || pathname === "/dashboard/content";
  const isDashboardPath = pathname === "/dashboard" || pathname === "/dashboard/profile";

  useEffect(() => {
    if (window.location.pathname !== pathname) {
      window.history.replaceState({}, "", pathname);
    }
    const handlePopState = () => {
      const currentPath = window.location.pathname;
      setPathname(currentPath === "/login" || currentPath === "/register" || currentPath === "/dashboard" || currentPath === "/dashboard/profile" || currentPath === "/dashboard/content" || isLessonPath(currentPath)
        ? currentPath
        : currentPath === "/dashboard/scores"
        ? "/dashboard"
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

  useEffect(() => {
    if (pathname === "/dashboard") setActiveId("");
  }, [pathname]);

  const handleDashboardNavigate = useCallback((page) => {
    if (page === "Weekly Review") {
      setPendingDashboardSection("pathways");
      navigate("/dashboard");
      return;
    }
    navigate(page === "Dashboard" ? "/dashboard" : page === "Student Profile" ? "/dashboard/profile" : "/dashboard/content");
  }, [navigate]);

  useEffect(() => {
    const handleExpiredSession = () => {
      setSessionUser(null);
      setSessionChecked(true);
      setAuthNotice("Your session expired. Please log in again.");
      navigate("/login");
    };
    window.addEventListener("smartplay-session-expired", handleExpiredSession);
    return () => window.removeEventListener("smartplay-session-expired", handleExpiredSession);
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
        if (!user) {
          setSessionUser(null);
          setSessionChecked(true);
          navigate("/login");
          return;
        }
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
    setAuthNotice("");
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

  const { loadContentItems, contentItems, contentBusy, contentSaving, handleContentUpload, handleContentDownload, handleContentDelete, handleMaterialSave, handleContentUpdate } = useContent({ pathname, sessionChecked, sessionUser, setError });

  const classroom = useTeacherDashboard({ enabled: isDashboardPath && sessionChecked && Boolean(sessionUser), studentId: activeId, sessionUser, contentItems, students, recommendations });
  const activeStudent = useMemo(
    () => students.find((student) => student.id === activeId),
    [students, activeId],
  );

  const loadStudents = useCallback(async () => {
    setBusy(true);
    setError("");
    try {
      const data = await api.students();

      const activeStudents = filterActiveStudents(data);

      setStudents(activeStudents);
      setActiveId((current) =>
        current && activeStudents.some((student) => student.id === current)
          ? current
          : "",
      );
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load learners.");
    } finally {
      setBusy(false);
    }
  }, []);

  const handleArchiveStudent = useCallback(async (studentId, confirmation) => {
    setError("");
    try {
      await archiveStudent({ api, studentId, confirmation, setStudents, setActiveId, setSuccessNotice, loadStudents });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not archive the student.");
      throw cause;
    }
  }, [loadStudents]);

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

  async function handleStudentTemplateDownload() {
    setError("");
    try {
      const blob = await api.downloadStudentTemplate();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "SmartPlay_Student_Profile_Import_Template.xlsx";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not download the student template.");
    }
  }

  async function handleStudentDetailsExport() {
    setError("");
    try {
      const blob = await api.exportStudentDetails();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = "SmartPlay_Student_Details.xlsx";
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not export student details.");
    }
  }

  async function handleStudentExcelImport(file) {
    setImportingStudents(true);
    setImportNotice("");
    setError("");
    try {
      const result = await api.importStudents(file);
      await loadStudents();
      setImportNotice(`${result.createdCount} student profile${result.createdCount === 1 ? "" : "s"} created successfully.`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not import students.");
    } finally {
      setImportingStudents(false);
    }
  }

  async function handleDeleteRecord(recordId) {
    if (!activeId || !recordId) return;

    const confirmed = window.confirm(
      "Delete this learning record? This action cannot be undone.",
    );
    if (!confirmed) return;

    setDeletingRecordId(recordId);
    setError("");
    try {
      await api.deleteRecord(activeId, recordId);
      await loadLearnerData(activeId);
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : "Could not delete this learning record.",
      );
    } finally {
      setDeletingRecordId("");
    }
  }

  async function handleGenerate() {
    if (!activeId || saving) return;
    const requestId = ++liveGenerationId.current;
    setSaving(true);
    setError("");
    setLivePrediction({ loading: true });
    try {
      const result = await api.generateLiveRecommendation(activeId);
      if (requestId !== liveGenerationId.current) return;
      setLivePrediction(result);
      setRecommendations((current) => {
        const returned = new Set(result.recommendations.map((item) => item.id));
        return [...result.recommendations, ...current.filter((item) => !returned.has(item.id))];
      });
    } catch (cause) {
      if (requestId !== liveGenerationId.current) return;
      setLivePrediction({ error: true, insufficient: cause.status === 422,
        message: cause instanceof Error ? cause.message : "Could not generate a pathway." });
    } finally {
      setSaving(false);
    }
  }

  async function handleAssignRecommendation(recommendation, materialId) {
    setSaving(true); setError("");
    try {
      await api.assignRecommendation(recommendation.id, materialId);
      setRecommendations((current) => current.map((item) => item.id === recommendation.id ? { ...item, materialId } : item));
      await classroom.refresh();
      setSuccessNotice("Approved activity assigned successfully.");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not assign the activity.");
    } finally { setSaving(false); }
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
      await classroom.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not update recommendation.");
    } finally {
      setSaving(false);
    }
  }

  const average = profile?.overallAverage ?? 0;
  const areas = profile?.areas ?? [];
  if (!isProtectedPath) {
    return (
      <AuthPage
        mode={pathname === "/login" ? "login" : "register"}
        notice={authNotice}
        onModeChange={(mode) => navigate(mode === "login" ? "/login" : "/register")}
        onSubmit={handleAuthSubmit}
      />
    );
  }

  if (!sessionChecked || !sessionUser) {
    return <main className="auth-loading">Checking your secure session…</main>;
  }

  if (isLessonPath(pathname)) {
    const materialId = decodeURIComponent(pathname.split("/").pop());
    return <WorkspaceLayout activePage="Dashboard" onNavigate={handleDashboardNavigate}
      teacherInitial={initials(sessionUser.name) || "T"} onLogout={handleLogout}>
      <LessonWorkspacePage key={materialId} materialId={materialId} onBack={() => navigate("/dashboard")} />
    </WorkspaceLayout>;
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
          onImported={loadContentItems}
          busy={contentBusy}
          saving={contentSaving}
          onUpload={handleContentUpload}
          onSaveMaterial={handleMaterialSave}
          onUpdate={handleContentUpdate}
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
        <SuccessToast message={successNotice} onDismiss={dismissSuccessNotice} />
        {busy && students.length === 0
          ? <div className="loading-state">Loading your student roster…</div>
          : (
            <StudentProfilePage
              onRefreshEvidence={loadLearnerData}
              contentItems={contentItems}
              contentBusy={contentBusy}
              onDownloadMaterial={handleContentDownload}
              activeStudent={activeStudent}
              areas={areas}
              average={average}
              dataBusy={learnerDataBusy}
              records={records}
              recommendations={recommendations}
              students={students}
              onAddMaterial={() => setShowMaterialForm(true)}
              onAddStudent={() => setShowStudentForm(true)}
              onDownloadTemplate={handleStudentTemplateDownload}
              onExportStudentDetails={handleStudentDetailsExport}
              onImportExcel={handleStudentExcelImport}
              importingStudents={importingStudents}
               importNotice={importNotice}
               onSelectStudent={setActiveId}
               onArchiveStudent={handleArchiveStudent}
               onDeleteRecord={handleDeleteRecord}
               deletingRecordId={deletingRecordId}
            />
          )}
        <footer className="page-footer">SMARTPLAY <span>·</span> A closer look at each learner’s progress.</footer>
        {showMaterialForm && (
          <MaterialEditor defaultGrade={activeStudent?.grade} onClose={() => setShowMaterialForm(false)} onSave={handleMaterialSave} />
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
      <SuccessToast message={successNotice} onDismiss={dismissSuccessNotice} />
      <DashboardPage
        classroom={classroom}
        {...{ sessionUser, activeId, students, activeStudent, records, profile, average, areas,
          recommendations, saving, busy, error, editingId, setActiveId, setShowMaterialForm,
          livePrediction, contentItems, handleAssignRecommendation, onAssignMaterial: setAssigningMaterial,
          setShowStudentForm, handleGenerate, setError, setEditingId, handleReview, navigate }}
        onCreatePracticeModule={(student, areas, pathway = []) => setPracticeModuleContext({
          student,
          description: areas.length
            ? `Teacher-reviewed practice focus from saved feedback: ${areas.map(area => `${area.category} — ${area.value}`).join("; ")}. Suggested steps: ${pathway.flatMap(area => area.steps.map((step, index) => `${area.category} ${index + 1}: ${step}`)).join(" ")}. Review and edit these suggestions before assigning this material.`
            : "Practice focus: teacher review of this learner's feedback categories. Add the chosen activity instructions.",
        })}
        recommendations={activeId ? recommendations : classroom.reviews}
      />
      {assigningMaterial && <MaterialEditor material={assigningMaterial} defaultGrade={activeStudent?.grade} onClose={() => setAssigningMaterial(null)} onSave={async metadata => {
        await handleContentUpdate(assigningMaterial, metadata);
        setAssigningMaterial(null);
        await classroom.refresh();
      }} />}
      {showMaterialForm && (
          <MaterialEditor defaultGrade={activeStudent?.grade} onClose={() => setShowMaterialForm(false)} onSave={handleMaterialSave} />
        )}
      {practiceModuleContext && <MaterialEditor
        key={practiceModuleContext.student.id}
        defaultGrade={practiceModuleContext.student.grade}
        defaultStudentId={practiceModuleContext.student.id}
        defaultStudentName={practiceModuleContext.student.name}
        defaultDescription={practiceModuleContext.description}
        onClose={() => setPracticeModuleContext(null)}
        onSave={handleMaterialSave}
      />}
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

export default App;
