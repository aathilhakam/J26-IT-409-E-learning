package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import lk.smartplay.adaptive.dto.ContentMetadataRequest;
import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.repository.ContentAssetRepository;
import org.springframework.web.multipart.MultipartFile;

class GradeOneBookImportServiceTest {
    @TempDir Path source;
    ContentAssetService content;
    ContentAssetRepository repository;
    GradeOneBookImportService service;
    @BeforeEach void setup() {
        content=mock(ContentAssetService.class); repository=mock(ContentAssetRepository.class);
        service=new GradeOneBookImportService(content,repository,source.toString(),"20MB");
    }
    @Test void importsPrivateGradeOneMetadataThroughExistingStorageAndSkipsOwnedHash() throws Exception {
        var book=GradeOneBookImportService.BOOKS.get(0);
        Files.writeString(source.resolve(book.filename()),"%PDF-1.7 test");
        when(repository.findByIdAndOwnerId(anyString(),eq("teacher"))).thenReturn(Optional.empty());
        when(content.store(eq("teacher"),any(),any(),anyString())).thenAnswer(invocation -> {
            ContentMetadataRequest metadata=invocation.getArgument(1); MultipartFile file=invocation.getArgument(2);
            assertEquals("Grade 1",metadata.grade());assertEquals("LIBRARY",metadata.assignmentMode()); assertEquals("TEACHER_ONLY",metadata.visibility()); assertTrue(metadata.studentIds().isEmpty());
            assertEquals(book.filename(),file.getOriginalFilename());assertEquals("application/pdf",file.getContentType());
            var asset=new ContentAsset("teacher",metadata.title(),metadata.category(),metadata.description(),file.getOriginalFilename(),"stored.pdf",file.getContentType(),file.getSize());
            when(repository.findByIdAndOwnerId(anyString(),eq("teacher"))).thenReturn(Optional.of(asset));
            return asset;
        });
        assertEquals("IMPORTED",service.importBooks("teacher",List.of(book)).get(0).status());
        assertEquals("SKIPPED",service.importBooks("teacher",List.of(new GradeOneBookImportService.Book(book.filename(),"Edited title","","","Learning Book"))).get(0).status());
        verify(content,times(1)).store(eq("teacher"),any(),any(),anyString());
        service.importBooks("another",List.of(book));
        verify(repository,atLeastOnce()).findByIdAndOwnerId(anyString(),eq("another"));
    }
    @Test void missingInvalidOversizedAndSymlinkSourcesNeverReachStorage() throws Exception {
        var book=GradeOneBookImportService.BOOKS.get(0);
        assertEquals("MISSING",service.importBooks("teacher",List.of(book)).get(0).status());
        Files.writeString(source.resolve(book.filename()),"not a PDF");
        assertEquals("FAILED",service.importBooks("teacher",List.of(book)).get(0).status());
        Files.writeString(source.resolve(book.filename()),"%PDF-1.7 bigger than tiny limit");
        var tiny=new GradeOneBookImportService(content,repository,source.toString(),"5B");
        assertEquals("FAILED",tiny.importBooks("teacher",List.of(book)).get(0).status());
        Files.delete(source.resolve(book.filename())); Files.createSymbolicLink(source.resolve(book.filename()),source.resolve("other.pdf"));
        assertEquals("FAILED",service.importBooks("teacher",List.of(book)).get(0).status());
        verifyNoInteractions(content);
    }
    @Test void rejectsUnapprovedOrRepeatedNames() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.importBooks("teacher",List.of(new GradeOneBookImportService.Book("../Weather.pdf","x","","","Learning Book"))));
        var b=GradeOneBookImportService.BOOKS.get(0);
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.importBooks("teacher",List.of(b,b)));
        assertEquals(8,service.candidates().size());verifyNoInteractions(content);
    }
}
