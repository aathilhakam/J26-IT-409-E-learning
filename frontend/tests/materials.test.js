import test from "node:test";
import assert from "node:assert/strict";
import { fileError, materialMetadata, materialError, filterMaterials, materialUploadBody } from "../src/features/content/materials.js";

const values = { title: " Counting shapes ", description: " Practice ", subject: " Mathematics ", grade: "Grade 1", materialType: "Worksheet", tags: "counting, shapes, counting" };
const pdf = new Blob(["%PDF-1.7 material"], { type: "application/pdf" });
Object.defineProperty(pdf, "name", { value: "lesson.pdf" });

test("material metadata normalizes values without accepting an owner or visibility override", () => {
  assert.deepEqual(materialMetadata({ ...values, ownerId: "forged", visibility: "PUBLIC" }), {
    title: "Counting shapes", description: "Practice", subject: "Mathematics", grade: "Grade 1",
    materialType: "Worksheet", category: "Worksheet", tags: ["counting", "shapes"], visibility: "TEACHER_ONLY",
  });
});

test("material form rejects missing or invalid metadata and files before submission", () => {
  const metadata = materialMetadata(values);
  assert.equal(materialError(metadata, pdf), "");
  assert.match(materialError({ ...metadata, title: "" }, pdf), /Title/);
  assert.match(materialError({ ...metadata, description: "x".repeat(1001) }, pdf), /Description/);
  assert.equal(materialError({ ...metadata, subject: "" }, pdf), "");
  assert.match(materialError({ ...metadata, subject: "x".repeat(101) }, pdf), /Subject/);
  assert.match(materialError({ ...metadata, grade: "" }, pdf), /grade/);
  assert.match(materialError({ ...metadata, materialType: "Invalid" }, pdf), /material type/);
  assert.match(materialError({ ...metadata, tags: ["x".repeat(41)] }, pdf), /tags/);
  assert.match(materialError(metadata, null), /non-empty file/);
  assert.equal(materialError(metadata, null, true), "");
});

test("uploads reject empty, unsupported, mismatched, and oversized files", () => {
  assert.match(fileError({ name: "empty.pdf", size: 0 }), /non-empty/);
  assert.match(fileError({ name: "run.exe", size: 12 }), /PDF/);
  assert.match(fileError({ name: "photo.png", type: "application/pdf", size: 12 }), /matching/);
  assert.match(fileError({ name: "big.pdf", type: "application/pdf", size: 20 * 1024 * 1024 + 1 }), /20 MB/);
  assert.equal(fileError({ name: "max.PDF", type: "application/pdf", size: 20 * 1024 * 1024 }), "");
});

test("multipart submission preserves actual file and all material fields and tags", async () => {
  const metadata = materialMetadata(values);
  const form = materialUploadBody(pdf, metadata);
  for (const key of ["title", "description", "subject", "grade", "materialType", "category", "visibility"]) assert.equal(form.get(key), metadata[key]);
  assert.deepEqual(form.getAll("tags"), ["counting", "shapes"]);
  assert.equal(form.has("ownerId"), false);
  assert.equal(await form.get("file").text(), "%PDF-1.7 material");
});

test("library combines search and subject, grade, type and legacy category filters", () => {
  const items = [{ id: "1", ...materialMetadata(values) }, { id: "2", title: "Alphabet", subject: "English", grade: "Grade 2", materialType: "Teacher Guide", category: "Lesson" }, { id: "3", title: "Old resource", category: "Other" }];
  assert.deepEqual(filterMaterials(items, { search: "COUNT", subject: "Mathematics", grade: "Grade 1", materialType: "Worksheet" }).map((item) => item.id), ["1"]);
  assert.deepEqual(filterMaterials(items, { subject: "English", grade: "Grade 1" }), []);
  assert.deepEqual(filterMaterials(items, { category: "Other" }).map((item) => item.id), ["3"]);
  assert.equal(filterMaterials(items, {}).length, 3);
});

test("multipart upload sends assignment mode, grade and repeated student IDs", () => {
  const form = materialUploadBody(pdf, { ...materialMetadata(values), assignmentMode: "STUDENTS", studentIds: ["1", "2"], assignmentGrade: "" });
  assert.equal(form.get("assignmentMode"), "STUDENTS");
  assert.deepEqual(form.getAll("studentIds"), ["1", "2"]);
  const grade = materialUploadBody(pdf, { ...materialMetadata(values), assignmentMode: "GRADE", assignmentGrade: "Grade 1" });
  assert.equal(grade.get("assignmentGrade"), "Grade 1");
});
