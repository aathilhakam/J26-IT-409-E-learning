
const API_BASE = import.meta.env.VITE_API_URL || "/api";

let csrfToken;

async function request(path, init) {
  const method = init?.method ?? "GET";
  const isFormData =
    typeof FormData !== "undefined" &&
    init?.body instanceof FormData;

  const headers = {
    Accept: "application/json",
    ...(!isFormData ? { "Content-Type": "application/json" } : {}),
    ...init?.headers,
  };

  // Add CSRF protection for requests that modify data
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
      typeof payload === "object" &&
      payload !== null &&
      "message" in payload
        ? String(payload.message)
        : typeof payload === "object" &&
            payload !== null &&
            "detail" in payload
          ? String(payload.detail)
          : `Request failed (${response.status})`;

    throw new Error(message);
  }

  if (response.status === 204) return null;

  return response.json();
}

async function requestBlob(path) {
  const response = await fetch(`${API_BASE}${path}`, {
    credentials: "same-origin",
    headers: {
      Accept: "application/octet-stream",
    },
  });

  if (!response.ok) {
    const payload = await response.json().catch(() => null);

    const message =
      typeof payload === "object" &&
      payload !== null &&
      "message" in payload
        ? String(payload.message)
        : `Request failed (${response.status})`;

    throw new Error(message);
  }

  return response.blob();
}

async function loadCsrfToken() {
  const response = await fetch(`${API_BASE}/auth/csrf`, {
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
    },
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
  // =========================
  // AUTHENTICATION
  // =========================

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

  // =========================
  // STUDENT MANAGEMENT
  // =========================

  students: () => request("/students"),

  createStudent: (name, grade) =>
    request("/students", {
      method: "POST",
      body: JSON.stringify({ name, grade }),
    }),

  profile: (studentId) =>
    request(`/students/${encodeURIComponent(studentId)}/profile`),

  // =========================
  // STUDENT LEARNING RECORDS
  // =========================

  records: (studentId) =>
    request(`/students/${encodeURIComponent(studentId)}/records`),

  addRecord: (studentId, record) =>
    request(`/students/${encodeURIComponent(studentId)}/records`, {
      method: "POST",
      body: JSON.stringify(record),
    }),

  // =========================
  // EXISTING RECOMMENDATIONS
  // =========================

  recommendations: (studentId) =>
    request(
      `/students/${encodeURIComponent(studentId)}/recommendations`
    ),

  generateRecommendation: (studentId) =>
    request(
      `/students/${encodeURIComponent(studentId)}/recommendations`,
      {
        method: "POST",
      }
    ),

  reviewRecommendation: (id, review) =>
    request(`/recommendations/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify(review),
    }),

  // =========================
  // NEW: RANDOM FOREST AI
  // =========================

  // Retrieves the predicted learning outcome,
  // prediction probabilities and personalized
  // learning path from the Python AI service
  // through the Spring Boot backend.

  aiRecommendation: (studentId) =>
    request(
      `/recommendations/student/${encodeURIComponent(studentId)}`
    ),

  // =========================
  // CONTENT MANAGEMENT
  // =========================

  content: () => request("/content"),

  uploadContent: (formData) =>
    request("/content", {
      method: "POST",
      body: formData,
    }),

  downloadContent: (id) =>
    requestBlob(
      `/content/${encodeURIComponent(id)}/download`
    ),

  deleteContent: (id) =>
    request(`/content/${encodeURIComponent(id)}`, {
      method: "DELETE",
    }),
};
