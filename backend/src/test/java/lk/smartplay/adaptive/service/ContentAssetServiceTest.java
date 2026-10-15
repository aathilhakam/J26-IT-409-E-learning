package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

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

    private ContentAssetService contentService;

    @BeforeEach
    void setUp() {
        contentService = new ContentAssetService(repository, tempDirectory.toString());
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
}
