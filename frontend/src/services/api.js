
const API_BASE = import.meta.env.VITE_API_URL || "/api";

let csrfToken;

async function request(path, init = {}) {
  const method = init.method ?? "GET";
  const isFormData =
    typeof FormData !== "undefined" && init.body instanceof FormData;

  const headers = {
    Accept: "application/json",
    ...(!isFormData ? { "Content-Type": "application/json" } : {}),
    ...init.headers,
  };

  if (["POST", "PUT", "PATCH", "DELETE"].includes(method.toUpperCase())) {
    csrfToken ??= await loadCsrfToken();
    headers["X-XSRF-TOKEN"] = csrfToken;
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    credentials: "same-origin",
    headers,
  });

  if (!response.ok) {
    const payload = await response.json().catch(() => null);

    const message =
      payload &&
      typeof payload === "object" &&
      (payload.message || payload.detail);

    const error = new Error(message ? String(message) : `Request failed (${response.status})`);
    error.status = response.status;
    throw error;
  }

  if (method !== "GET" && typeof window !== "undefined" && (/^\/(students|recommendations|worksheet-scores|dashboard\/lessons)/).test(path)) {
    window.dispatchEvent(new CustomEvent("smartplay-evidence-updated", { detail: { path, method } }));
  }
  if (response.status === 204) {
    return null;
  }

  // Some successful endpoints may return an empty response.
  if (response.headers.get("content-length") === "0") {
    return null;
  }

  return response.json();
}

async function requestBlob(path) {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: "same-origin",
    cache: "no-store",
    headers: { Accept: "application/octet-stream" },
  });

  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    throw new Error(payload?.message || payload?.detail || `Request failed (${response.status})`);
  }

  return response.blob();
}

async function loadCsrfToken() {
  const response = await fetch(`${API_BASE}/auth/csrf`, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    throw new Error(
      `Could not prepare a secure request (${response.status})`
    );
  }

  const payload = await response.json();
  return payload.csrfToken;
}

export const api = {
  // =========================================
  // AUTHENTICATION
  // =========================================

  register: (account) =>
    request("/auth/register", {
      method: "POST",
      body: JSON.stringify(account),
    }),

  login: (credentials) =>
    request("/auth/login", {
      method: "POST",
      body: JSON.stringify(credentials),
    }),

  currentUser: () => request("/auth/me"),

  logout: () =>
    request("/auth/logout", {
      method: "POST",
    }),

  // =========================================
  // STUDENT MANAGEMENT
  // =========================================

  gradeOneBooks: () => request("/content/import-grade-one"),
  importGradeOneBooks: (books) => request("/content/import-grade-one", { method: "POST", body: JSON.stringify(books) }),
  dashboardStatistics: (studentId) => request(`/dashboard/statistics${studentId ? `?studentId=${encodeURIComponent(studentId)}` : ""}`),
  studentFeedback: (studentId) => request(`/students/${encodeURIComponent(studentId)}/feedback`),
  teacherFeedback: (status = "SUBMITTED") => request(`/dashboard/feedback?status=${encodeURIComponent(status)}`),
  saveLessonFeedbackDrafts: (materialId, payload) => request(`/dashboard/lessons/${encodeURIComponent(materialId)}/feedback/drafts`, { method: "PUT", body: JSON.stringify(payload) }),
  lessonWorkspace: (materialId) => request(`/dashboard/lessons/${encodeURIComponent(materialId)}`),
  saveLessonFeedback: (materialId, payload) => request(`/dashboard/lessons/${encodeURIComponent(materialId)}/feedback`, { method: "POST", body: JSON.stringify(payload) }),
  completeClassroomLesson: (materialId) => request(`/dashboard/lessons/${encodeURIComponent(materialId)}/complete`, { method: "POST" }),
  classroomLessons: (grade) => request(`/dashboard/lessons${grade ? `?grade=${encodeURIComponent(grade)}` : ""}`),
  completedLessons: (grade) => request(`/dashboard/lessons/history${grade ? `?grade=${encodeURIComponent(grade)}` : ""}`),
  pendingReviews: () => request("/dashboard/reviews"),
  teacherPathways: () => request("/dashboard/pathways"),

  students: () => request("/students"),

  createStudent: (name, grade) =>
    request("/students", {
      method: "POST",
      body: JSON.stringify({ name, grade }),
    }),

  // Get an individual student
  student: (studentId) =>
    request(`/students/${encodeURIComponent(studentId)}`),

  // =========================================
  // ARCHIVE AND RESTORE STUDENTS
  // =========================================

  // Archive a student after confirming their full name in the frontend form.
  archiveStudent: (studentId, confirmation) =>
    request(
      `/students/${encodeURIComponent(studentId)}/archive`,
      {
        method: "PATCH",
        body: JSON.stringify({
          studentFullName: confirmation.studentFullName,
        }),
      }
    ),

  // Get archived students
  archivedStudents: () => request("/students/archived"),

  // Restore an archived student
  restoreStudent: (studentId) =>
    request(
      `/students/${encodeURIComponent(studentId)}/restore`,
      {
        method: "PATCH",
      }
    ),

  // =========================================
  // STUDENT PROFILE
  // =========================================

  profile: (studentId) =>
    request(
      `/students/${encodeURIComponent(studentId)}/profile`
    ),

  // =========================================
  // STUDENT EXCEL IMPORT
  // =========================================

  downloadStudentTemplate: () =>
    requestBlob("/students/template"),
  exportStudentDetails: () => requestBlob("/students/export"),

  importStudents: (file) => {
    const body = new FormData();
    body.append("file", file);

    return request("/students/import", {
      method: "POST",
      body,
    });
  },

  // =========================================
  // LEARNING RECORDS
  // =========================================

  observations: (studentId) => request(`/students/${encodeURIComponent(studentId)}/observations`),
  addObservation: (studentId, payload) => request(`/students/${encodeURIComponent(studentId)}/observations`, { method: "POST", body: JSON.stringify(payload) }),
  lessonHistory: (studentId) => request(`/students/${encodeURIComponent(studentId)}/history`),
  addHistoricalLesson: (studentId, payload) => request(`/students/${encodeURIComponent(studentId)}/history`, { method: "POST", body: JSON.stringify(payload) }),
  assessments: (studentId) => request(`/students/${encodeURIComponent(studentId)}/assessments`),
  addAssessment: (studentId, payload) => request(`/students/${encodeURIComponent(studentId)}/assessments`, { method: "POST", body: JSON.stringify(payload) }),

  records: (studentId) =>
    request(
      `/students/${encodeURIComponent(studentId)}/records`
    ),

  addRecord: (studentId, record) =>
    request(
      `/students/${encodeURIComponent(studentId)}/records`,
      {
        method: "POST",
        body: JSON.stringify(record),
      }
    ),

  // =========================================
  // LEARNING RECOMMENDATIONS
  // =========================================

  recommendations: (studentId) =>
    request(
      `/students/${encodeURIComponent(studentId)}/recommendations`
    ),

  liveFeatures: (studentId) => request(`/recommendations/student/${encodeURIComponent(studentId)}/features`),
  downloadScoreTemplate: (parameters) => requestBlob(`/worksheet-scores/template?${new URLSearchParams(parameters)}`),
  previewWorksheetScores: (form) => request("/worksheet-scores/preview", { method: "POST", body: form }),
  importScores: async (form, token) => {
    const confirmed = new FormData();
    for (const [key, value] of form.entries()) confirmed.append(key, value);
    confirmed.append("token", token);
    return request("/worksheet-scores/import", { method: "POST", body: confirmed });
  },
  worksheetScores: async () => {
    try {
      return await request("/worksheet-scores/results");
    } catch (error) {
      // Support the previous backend while it is being restarted with the teacher-wide route.
      if (error.status !== 404) throw error;
      const roster = await request("/students");
      const students = roster.filter(student => student.active !== false);
      const assessments = await Promise.all(students.map(student => request(`/students/${encodeURIComponent(student.id)}/assessments`)));
      return assessments.flat().filter(result => result.worksheet === true || result.assessmentType === "WORKSHEET");
    }
  },
  correctWorksheetScore: (id, correction) => request(`/worksheet-scores/results/${encodeURIComponent(id)}`, { method: "PATCH", body: JSON.stringify(correction) }),
  milestoneProgress: (studentId) => request(`/recommendations/student/${encodeURIComponent(studentId)}/milestones`),
  evaluateMilestones: (studentId) => request(`/recommendations/student/${encodeURIComponent(studentId)}/milestones?retry=true`, { method: "POST" }),
  generateLiveRecommendation: (studentId) =>
    request(`/recommendations/student/${encodeURIComponent(studentId)}/pathway`, { method: "POST" }),

  assignRecommendation: (id, materialId) =>
    request(`/recommendations/${encodeURIComponent(id)}/assign`, {
      method: "POST", body: JSON.stringify({ materialId }),
    }),

  generateRecommendation: (studentId) =>
    request(
      `/students/${encodeURIComponent(studentId)}/recommendations`,
      {
        method: "POST",
      }
    ),

  reviewRecommendation: (id, review) =>
    request(
      `/recommendations/${encodeURIComponent(id)}`,
      {
        method: "PATCH",
        body: JSON.stringify(review),
      }
    ),

  aiRecommendation: (studentId) =>
    request(
      `/recommendations/student/${encodeURIComponent(studentId)}`
    ),

  // =========================================
  // CONTENT MANAGEMENT
  // =========================================

  content: () => request("/content"),

  uploadContent: (formData, requestKey) =>
    request("/content", {
      method: "POST",
      body: formData,
      ...(requestKey ? { headers: { "Idempotency-Key": requestKey } } : {}),
    }),

  updateContent: (id, metadata) =>
    request(`/content/${encodeURIComponent(id)}`, {
      method: "PUT",
      body: JSON.stringify(metadata),
    }),

  downloadContent: (id) =>
    requestBlob(
      `/content/${encodeURIComponent(id)}/download`
    ),

  deleteContent: (id) =>
    request(
      `/content/${encodeURIComponent(id)}`,
      {
        method: "DELETE",
      }
    ),
};
