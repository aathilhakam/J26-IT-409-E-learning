package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import lk.smartplay.adaptive.dto.ContentMetadataRequest;
import lk.smartplay.adaptive.repository.ContentAssetRepository;

// Each opted-in run uses and drops only its own uniquely named test database.
@EnabledIfEnvironmentVariable(named = "MONGODB_TEST_URI", matches = ".+")
class ContentAssetPersistenceTest {
    @TempDir Path storage;

    @Test
    void materialSurvivesNewConnectionAndServiceThenCanBeEditedAndDeleted() throws Exception {
        String uri = System.getenv("MONGODB_TEST_URI");
        String database = "smartplay_material_test_" + UUID.randomUUID().toString().replace("-", "");
        byte[] pdf = "%PDF-1.7 persistent learning material".getBytes();
        try (var client = MongoClients.create(uri)) {
            MongoTemplate template = new MongoTemplate(client, database);
            try {
                ContentAssetRepository repository = new MongoRepositoryFactory(template).getRepository(ContentAssetRepository.class);
                ContentAssetService service = new ContentAssetService(repository, org.mockito.Mockito.mock(lk.smartplay.adaptive.repository.StudentRepository.class), storage.toString());
                var metadata = new ContentMetadataRequest("Count shapes", "Worksheet", "Practice counting", "Math", "Grade 1",
                        "Worksheet", List.of("counting", "shapes"), "TEACHER_ONLY");
                var saved = service.store("teacher-1", metadata,
                        new MockMultipartFile("file", "count.pdf", "application/pdf", pdf));
                assertNotNull(saved.getId());
                Document stored = template.getCollection("content_assets").find().first();
                assertNotNull(stored);
                assertEquals("Math", stored.getString("subject"));
                assertEquals("Grade 1", stored.getString("grade"));
                assertEquals("teacher-1", stored.getString("ownerId"));
                assertEquals(saved.getStorageFileName(), stored.getString("storageFileName"));
                assertEquals(List.of("counting", "shapes"), stored.getList("tags", String.class));
                assertFalse(stored.containsKey("file"));
                assertFalse(stored.containsKey("base64"));
                // Recreate connection, repository and service to check persisted metadata/file references.
                try (var reconnected = MongoClients.create(uri)) {
                    var reloadedRepository = new MongoRepositoryFactory(new MongoTemplate(reconnected, database)).getRepository(ContentAssetRepository.class);
                    var reloaded = new ContentAssetService(reloadedRepository, org.mockito.Mockito.mock(lk.smartplay.adaptive.repository.StudentRepository.class), storage.toString());
                    var fetched = reloaded.findOwned(saved.getId(), "teacher-1");
                    assertEquals("Count shapes", fetched.getTitle());
                    assertEquals("Worksheet", fetched.getMaterialType());
                    assertEquals("TEACHER_ONLY", fetched.getVisibility());
                    assertNotNull(fetched.getUploadedAt());
                    assertArrayEquals(pdf, Files.readAllBytes(reloaded.fileFor(fetched)));
                    assertEquals(1, reloaded.findAll("teacher-1").size());
                    assertTrue(reloaded.findAll("teacher-2").isEmpty());
                    assertThrows(ResponseStatusException.class, () -> reloaded.findOwned(saved.getId(), "teacher-2"));
                    assertThrows(ResponseStatusException.class, () -> reloaded.delete(saved.getId(), "teacher-2"));
                    assertThrows(ResponseStatusException.class, () -> reloaded.update(saved.getId(), "teacher-2", metadata));
                    var edited = reloaded.update(saved.getId(), "teacher-1", new ContentMetadataRequest(
                            "Count more shapes", "Activity", "Updated", "Math", "Grade 2", "Activity Sheet", List.of("shapes"), "TEACHER_ONLY"));
                    assertEquals("Grade 2", edited.getGrade());
                    assertEquals("Count more shapes", reloaded.findAll("teacher-1").get(0).title());
                    assertArrayEquals(pdf, Files.readAllBytes(reloaded.fileFor(edited)));
                    reloaded.delete(saved.getId(), "teacher-1");
                    assertEquals(0, reloadedRepository.count());
                    assertFalse(Files.exists(storage.resolve(saved.getStorageFileName())));
                }
            } finally { template.getDb().drop(); }
        }
    }
}
