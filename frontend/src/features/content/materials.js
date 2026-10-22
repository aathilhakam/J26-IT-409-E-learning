export const MATERIAL_TYPES = ["Teacher Guide", "Lesson Plan", "Worksheet", "Activity Sheet", "Reading Material", "Image", "Other", "Activity Book", "Song Book", "Learning Book"];
export const FILE_ACCEPT = ".pdf,.jpg,.jpeg,.png,.webp,application/pdf,image/jpeg,image/png,image/webp";
export const MAX_FILE_SIZE = 20 * 1024 * 1024;

export function fileError(file) {
  if (!file || file.size === 0) return "Choose a non-empty file to upload.";
  const types = { pdf: "application/pdf", jpg: "image/jpeg", jpeg: "image/jpeg", png: "image/png", webp: "image/webp" };
  const expected = types[file.name.split(".").pop()?.toLowerCase()];
  if (!expected || (file.type && file.type !== expected)) return "Use a PDF, JPG, PNG, or WEBP file with a matching file type.";
  if (file.size > MAX_FILE_SIZE) return "Files must be 20 MB or smaller.";
  return "";
}

export function formatContentSize(size) {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

export function materialCategory(type) {
  return ({ "Teacher Guide": "Lesson", "Lesson Plan": "Lesson", Worksheet: "Worksheet", "Activity Sheet": "Activity", "Reading Material": "Reading" })[type] || "Other";
}

export function materialMetadata(values) {
  const materialType = values.materialType;
  return {
    title: values.title.trim(), description: values.description.trim(), subject: values.subject.trim(),
    grade: values.grade.trim(), materialType, category: materialCategory(materialType),
    tags: [...new Set(values.tags.split(",").map((tag) => tag.trim()).filter(Boolean))],
    visibility: "TEACHER_ONLY",
  };
}

export function materialError(metadata, file, editing = false) {
  if (!metadata.title || metadata.title.length > 120) return "Title must be between 1 and 120 characters.";
  if (metadata.description.length > 1000) return "Description must be at most 1000 characters.";
  if (metadata.subject.length > 100) return "Subject must be at most 100 characters.";
  if (!metadata.grade || metadata.grade.length > 60) return "Choose a grade or age group (up to 60 characters).";
  if (!MATERIAL_TYPES.includes(metadata.materialType)) return "Choose a valid material type.";
  if (metadata.tags.length > 20 || metadata.tags.some((tag) => tag.length > 40)) return "Use up to 20 tags, each at most 40 characters.";
  return editing ? "" : fileError(file);
}

export function filterMaterials(items, { search = "", category = "", subject = "", grade = "", materialType = "" }) {
  const query = search.trim().toLowerCase();
  return items.filter((item) =>
    (!query || [item.title, item.description, item.originalFileName, ...(item.tags || [])].some((value) => value?.toLowerCase().includes(query))) &&
    (!category || item.category === category) && (!subject || item.subject === subject) &&
    (!grade || item.grade === grade) && (!materialType || item.materialType === materialType));
}

export function materialUploadBody(file, metadata) {
  const form = new FormData();
  for (const key of ["title", "category", "description", "subject", "grade", "materialType", "visibility", "assignmentMode", "assignmentGrade"]) {
    if (metadata[key] != null) form.append(key, metadata[key]);
  }
  for (const id of metadata.studentIds || []) form.append("studentIds", id);
  for (const tag of metadata.tags || []) form.append("tags", tag);
  form.append("file", file);
  return form;
}
