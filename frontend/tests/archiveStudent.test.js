import test from "node:test";
import assert from "node:assert/strict";
import { archiveStudent, activeStudents } from "../src/features/students/archiveStudent.js";

function fixture(students, activeId, failure) {
  const state = { students, activeId, notice: "", refreshes: 0 };
  const confirmation = { studentFullName: "Amaya Silva" };
  const options = {
    studentId: "1", confirmation,
    api: { archiveStudent: async (id, body) => {
      assert.equal(id, "1");
      assert.equal(body, confirmation);
      assert.equal(state.students, students);
      if (failure) throw failure;
    } },
    setStudents: (update) => { state.students = update(state.students); },
    setActiveId: (update) => { state.activeId = update(state.activeId); },
    setSuccessNotice: (notice) => { state.notice = notice; },
    loadStudents: async () => { state.refreshes++; },
  };
  return { state, options };
}

test("successful archive updates active roster/count, clears selection and retains success notice", async () => {
  const { state, options } = fixture([{ id: "1" }, { id: "2" }], "1");
  await archiveStudent(options);
  assert.deepEqual(state.students, [{ id: "2" }]);
  assert.equal(state.students.length, 1);
  assert.equal(state.activeId, "");
  assert.match(state.notice, /Student removed successfully/);
  assert.equal(state.refreshes, 1);
});

test("failed archive preserves roster, selection and notice, and propagates the error", async () => {
  const error = new Error("Full name does not match");
  const { state, options } = fixture([{ id: "1" }], "1", error);
  await assert.rejects(archiveStudent(options), error);
  assert.deepEqual(state.students, [{ id: "1" }]);
  assert.equal(state.activeId, "1");
  assert.equal(state.notice, "");
  assert.equal(state.refreshes, 0);
});

test("archiving the final student leaves an empty roster and visible notification", async () => {
  const { state, options } = fixture([{ id: "1" }], "1");
  await archiveStudent(options);
  assert.deepEqual(state.students, []);
  assert.equal(state.activeId, "");
  assert.ok(state.notice);
});

test("archiving another student preserves current selection", async () => {
  const { state, options } = fixture([{ id: "1" }, { id: "2" }], "2");
  await archiveStudent(options);
  assert.equal(state.activeId, "2");
});

test("active roster excludes archived records and supports older student documents", () => {
  assert.deepEqual(activeStudents([{ id: "1" }, { active: false }, { isActive: false }, { archived: true }, { id: "2", active: true }]), [{ id: "1" }, { id: "2", active: true }]);
  assert.deepEqual(activeStudents([]), []);
});
