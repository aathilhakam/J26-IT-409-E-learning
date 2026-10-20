const API_BASE = import.meta.env.VITE_API_URL || "/api";
const VOICE_API_BASE = import.meta.env.VITE_VOICE_API_URL || "/voice-ai";
const IMAGE_API_BASE = import.meta.env.VITE_IMAGE_API_URL || "/image-ai";
let csrfToken;

async function request(path, init) {
  const method = init?.method ?? "GET";
  const isFormData = typeof FormData !== "undefined" && init?.body instanceof FormData;
  const headers = {
    Accept: "application/json",
    ...(!isFormData ? { "Content-Type": "application/json" } : {}),
    ...init?.headers,
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
      typeof payload === "object" && payload !== null && "message" in payload
        ? String(payload.message)
        : typeof payload === "object" && payload !== null && "detail" in payload
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
    headers: { Accept: "application/octet-stream" },
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const message =
      typeof payload === "object" && payload !== null && "message" in payload
        ? String(payload.message)
        : `Request failed (${response.status})`;
    throw new Error(message);
  }
  return response.blob();
}

async function requestVoicePrediction(file) {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch(`${VOICE_API_BASE}/predict/voice`, {
    method: "POST",
    body: formData,
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const message =
      typeof payload === "object" && payload !== null && "detail" in payload
        ? String(payload.detail)
        : `Voice analysis failed (${response.status})`;
    throw new Error(message);
  }
  const result = await response.json();
  if (
    typeof result !== "object" ||
    result === null ||
    result.model !== "Random Forest Regressor" ||
    typeof result.average_score !== "number" ||
    !Number.isFinite(result.average_score) ||
    typeof result.scores !== "object" ||
    result.scores === null ||
    Object.values(result.scores).length !== 6 ||
    Object.values(result.scores).some(
      (score) => typeof score !== "number" || !Number.isFinite(score) || score < 1 || score > 5,
    )
  ) {
    throw new Error("The voice model returned an invalid prediction response.");
  }
  return result;
}

async function requestImagePrediction(file) {
  const formData = new FormData();
  formData.append("file", file);

  const response = await fetch(`${IMAGE_API_BASE}/predict/image`, {
    method: "POST",
    body: formData,
    headers: { Accept: "application/json" },
  });

  if (!response.ok) {
    const payload = await response.json().catch(() => null);
    const message =
      typeof payload === "object" && payload !== null && "detail" in payload
        ? String(payload.detail)
        : `Image analysis failed (${response.status})`;
    throw new Error(message);
  }
  const result = await response.json();
  const expectedTargets = [
    "originality",
    "elaboration",
    "color_exploration",
    "detail",
    "visual_organization",
  ];
  if (
    typeof result !== "object" ||
    result === null ||
    result.model !== "Random Forest Image Regressor" ||
    typeof result.average_score !== "number" ||
    !Number.isFinite(result.average_score) ||
    typeof result.scores !== "object" ||
    result.scores === null ||
    Object.keys(result.scores).length !== expectedTargets.length ||
    expectedTargets.some(
      (target) =>
        typeof result.scores[target] !== "number" ||
        !Number.isFinite(result.scores[target]) ||
        result.scores[target] < 1 ||
        result.scores[target] > 5,
    )
  ) {
    throw new Error("The image model returned an invalid prediction response.");
  }
  return result;
}

async function loadCsrfToken() {
  const response = await fetch(`${API_BASE}/auth/csrf`, {
    credentials: "same-origin",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(`Could not prepare a secure request (${response.status})`);
  }
  const payload = await response.json();
  return payload.csrfToken;
}

export const api = {
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
  logout: () => request("/auth/logout", { method: "POST" }),
  students: () => request("/students"),
  createStudent: (name, grade) =>
    request("/students", {
      method: "POST",
      body: JSON.stringify({ name, grade }),
    }),
  profile: (studentId) =>
    request(`/students/${studentId}/profile`),
  records: (studentId) =>
    request(`/students/${studentId}/records`),
  addRecord: (
    studentId,
    record,
  ) =>
    request(`/students/${studentId}/records`, {
      method: "POST",
      body: JSON.stringify(record),
    }),
  analyzeVoice: (file) => requestVoicePrediction(file),
  analyzeImage: (file) => requestImagePrediction(file),
  recommendations: (studentId) =>
    request(`/students/${studentId}/recommendations`),
  generateRecommendation: (studentId) =>
    request(`/students/${studentId}/recommendations`, {
      method: "POST",
    }),
  reviewRecommendation: (
    id,
    review,
  ) =>
    request(`/recommendations/${id}`, {
      method: "PATCH",
      body: JSON.stringify(review),
    }),
  content: () => request("/content"),
  uploadContent: (formData) =>
    request("/content", {
      method: "POST",
      body: formData,
    }),
  downloadContent: (id) => requestBlob(`/content/${encodeURIComponent(id)}/download`),
  deleteContent: (id) =>
    request(`/content/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
