package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;

import lk.smartplay.adaptive.dto.ContentMetadataRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.repository.ContentAssetRepository;

@ExtendWith(MockitoExtension.class)
class ContentAssetServiceTest {
    private static final String OWNER_ID = "teacher-1";

    @Mock
    private ContentAssetRepository repository;

    @TempDir
    private Path tempDirectory;

    @Mock private lk.smartplay.adaptive.repository.StudentRepository students;
    private ContentAssetService contentService;

    @BeforeEach
    void setUp() {
        contentService = new ContentAssetService(repository, students, tempDirectory.toString());
    }

    @Test
    void storesAllowedPdfWithPrivateMetadata() throws Exception {
        byte[] pdfBytes = "%PDF-1.7 test content".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        MockMultipartFile file = new MockMultipartFile("file", "lesson.pdf", "application/pdf", pdfBytes);
        when(repository.save(any(ContentAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ContentAsset asset = contentService.store(OWNER_ID, "Lesson plan", "Lesson", "Week one", file);

        assertEquals(OWNER_ID, asset.getOwnerId());
        assertEquals("Lesson plan", asset.getTitle());
        assertEquals("lesson.pdf", asset.getOriginalFileName());
        assertEquals(pdfBytes.length, asset.getSize());
        assertEquals(pdfBytes.length, Files.size(contentService.fileFor(asset)));
    }

    @Test
    void rejectsFilesWithMismatchedSignatures() {
        MockMultipartFile fakePdf = new MockMultipartFile(
                "file", "lesson.pdf", "application/pdf", "not a pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> contentService.store(OWNER_ID, "Lesson plan", "Lesson", "", fakePdf));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    @Test
    void refusesAccessToAnotherTeachersContent() {
        when(repository.findByIdAndOwnerId("asset-1", OWNER_ID)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> contentService.findOwned("asset-1", OWNER_ID));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    }

    @Test
    void deletesStoredFileAndMetadata() throws Exception {
        byte[] pdfBytes = "%PDF-1.7 test content".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        ContentAsset asset = new ContentAsset(
                OWNER_ID, "Lesson plan", "Lesson", "", "lesson.pdf", "stored.pdf", "application/pdf", pdfBytes.length);
        Files.createDirectories(tempDirectory);
        Files.write(tempDirectory.resolve("stored.pdf"), pdfBytes);
        when(repository.findByIdAndOwnerId("asset-1", OWNER_ID)).thenReturn(Optional.of(asset));

        contentService.delete("asset-1", OWNER_ID);

        verify(repository).delete(asset);
        org.junit.jupiter.api.Assertions.assertFalse(Files.exists(tempDirectory.resolve("stored.pdf")));
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "lesson.pdf", "application/pdf", "%PDF-1.7 contents".getBytes());
    }

    private ContentMetadataRequest metadata(String title, String subject, String grade, String type, String visibility) {
        return new ContentMetadataRequest(title, "Worksheet", "Practice", subject, grade, type,
                List.of("counting", " group work "), visibility);
    }

    @Test
    void storesMaterialMetadataAlongWithThePersistentFile() throws Exception {
        when(repository.save(any(ContentAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ContentAsset asset = contentService.store(OWNER_ID,
                metadata(" Counting ", "Mathematics", "Grade 1", "Worksheet", "TEACHER_ONLY"), pdf());
        assertEquals("Counting", asset.getTitle());
        assertEquals("Mathematics", asset.getSubject());
        assertEquals("Grade 1", asset.getGrade());
        assertEquals("Worksheet", asset.getMaterialType());
        assertEquals(List.of("counting", "group work"), asset.getTags());
        assertEquals("TEACHER_ONLY", asset.getVisibility());
        verify(repository).save(asset);
        org.junit.jupiter.api.Assertions.assertArrayEquals(pdf().getBytes(), Files.readAllBytes(contentService.fileFor(asset)));
    }

    @Test
    void rejectsInvalidMetadataWithoutStoringFiles() throws Exception {
        for (ContentMetadataRequest invalid : List.of(
                metadata(" ", "Math", "Grade 1", "Worksheet", "TEACHER_ONLY"),
                metadata("Title", "x".repeat(101), "Grade 1", "Worksheet", "TEACHER_ONLY"),
                metadata("Title", "Math", "", "Worksheet", "TEACHER_ONLY"),
                metadata("Title", "Math", "Grade 1", "Invalid", "TEACHER_ONLY"),
                metadata("Title", "Math", "Grade 1", "Worksheet", "STUDENT_ACCESSIBLE"),
                new ContentMetadataRequest("Title", "Worksheet", "", "Math", "Grade 1", "Worksheet", List.of("x".repeat(41)), null))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> contentService.store(OWNER_ID, invalid, pdf())).getStatusCode());
        }
        try (var files = Files.list(tempDirectory)) { assertEquals(0, files.count()); }
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void rejectsEmptyUnsupportedAndOversizedFiles() {
        List<MockMultipartFile> badFiles = List.of(
                new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]),
                new MockMultipartFile("file", "program.exe", "application/octet-stream", new byte[] {1}),
                new MockMultipartFile("file", "lesson.pdf", "image/png", "%PDF-1.7".getBytes()));
        for (MockMultipartFile file : badFiles) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> contentService.store(OWNER_ID, "Title", "Lesson", "", file)).getStatusCode());
        }
        MockMultipartFile oversized = new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[20 * 1024 * 1024 + 1]);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(ResponseStatusException.class,
                () -> contentService.store(OWNER_ID, "Title", "Lesson", "", oversized)).getStatusCode());
    }

    @Test
    void metadataUpdateKeepsOwnerFileAndCreationTimestamp() {
        ContentAsset asset = new ContentAsset(OWNER_ID, "Old", "Lesson", "", "lesson.pdf", "stored.pdf", "application/pdf", 12);
        when(repository.findByIdAndOwnerId("asset-1", OWNER_ID)).thenReturn(Optional.of(asset));
        when(repository.save(asset)).thenReturn(asset);
        var createdAt = asset.getUploadedAt();
        ContentAsset updated = contentService.update("asset-1", OWNER_ID,
                metadata("New title", "Math", "Grade 2", "Worksheet", "TEACHER_ONLY"));
        assertEquals("New title", updated.getTitle());
        assertEquals(OWNER_ID, updated.getOwnerId());
        assertEquals("stored.pdf", updated.getStorageFileName());
        assertEquals(createdAt, updated.getUploadedAt());
    }

    @Test
    void updateAndDeleteRefuseAnotherTeachersMaterial() {
        when(repository.findByIdAndOwnerId("asset-1", OWNER_ID)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> contentService.update("asset-1", OWNER_ID,
                metadata("Title", "Math", "Grade 1", "Worksheet", "TEACHER_ONLY")));
        assertThrows(ResponseStatusException.class, () -> contentService.delete("asset-1", OWNER_ID));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).delete(any(ContentAsset.class));
    }

    @Test
    void databaseSaveFailureCleansUpUploadedFile() throws Exception {
        when(repository.save(any(ContentAsset.class))).thenThrow(new IllegalStateException("Database unavailable"));
        assertThrows(IllegalStateException.class, () -> contentService.store(OWNER_ID, "Title", "Lesson", "", pdf()));
        try (var files = Files.list(tempDirectory)) { assertEquals(0, files.count()); }
    }

    @Test
    void materialCanBeStoredWithoutSubject() {
        when(repository.save(any(ContentAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ContentAsset saved = contentService.store(OWNER_ID,
                metadata("Title", "", "Grade 1", "Worksheet", "TEACHER_ONLY"), pdf());
        assertEquals("", saved.getSubject());
        assertEquals("Grade 1", saved.getGrade());
    }

    private lk.smartplay.adaptive.model.Student learner(String id, String grade) {
        var student = new lk.smartplay.adaptive.model.Student("Learner " + id, grade, OWNER_ID);
        org.springframework.test.util.ReflectionTestUtils.setField(student, "id", id);
        return student;
    }

    @Test
    void assignsOneFileToMultipleStudentsGradeOrWholeClass() {
        var first = learner("1", "Grade 1");
        var second = learner("2", "Grade 2");
        when(students.findByOwnerId(OWNER_ID)).thenReturn(List.of(first, second));
        when(repository.save(any(ContentAsset.class))).thenAnswer(invocation -> invocation.getArgument(0));
        for (String mode : List.of("STUDENTS", "GRADE", "ALL")) {
            var request = new ContentMetadataRequest("Lesson", "Worksheet", "", "", "Grade 1", "Worksheet",
                    List.of(), "TEACHER_ONLY", mode, List.of("1", "2", "1"), "Grade 1");
            var saved = contentService.store(OWNER_ID, request, pdf());
            assertEquals(mode, saved.getAssignmentMode());
            assertEquals(mode.equals("GRADE") ? List.of("1") : List.of("1", "2"), saved.getAssignedStudentIds());
        }
    }

    @Test
    void refusesForeignInactiveAndEmptyStudentAssignments() {
        var inactive = learner("2", "Grade 1"); inactive.setActive(false);
        when(students.findByOwnerId(OWNER_ID)).thenReturn(List.of(learner("1", "Grade 1"), inactive));
        for (List<String> ids : List.of(List.of("foreign"), List.of("2"), List.<String>of())) {
            var request = new ContentMetadataRequest("Lesson", "Worksheet", "", "", "Grade 1", "Worksheet",
                    List.of(), null, "STUDENTS", ids, null);
            assertThrows(ResponseStatusException.class, () -> contentService.store(OWNER_ID, request, pdf()));
        }
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
