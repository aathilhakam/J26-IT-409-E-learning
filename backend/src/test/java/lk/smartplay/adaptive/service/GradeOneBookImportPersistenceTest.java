package lk.smartplay.adaptive.service;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.data.mongodb.core.MongoTemplate;
import lk.smartplay.adaptive.repository.ContentAssetRepository;
@SpringBootTest
@EnabledIfEnvironmentVariable(named="MONGODB_TEST_URI",matches=".+")
class GradeOneBookImportPersistenceTest {
    static final String DATABASE="smartplay_book_import_test_"+UUID.randomUUID().toString().replace("-","");
    static final Path DIRECTORY=Path.of(System.getProperty("java.io.tmpdir"),DATABASE);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.data.mongodb.uri",()->System.getenv("MONGODB_TEST_URI"));p.add("spring.data.mongodb.database",()->DATABASE);
        p.add("smartplay.content.storage-dir",()->DIRECTORY.resolve("stored").toString());
        p.add("smartplay.content.grade-one-source-dir",()->DIRECTORY.resolve("source").toString());
    }
    @Autowired GradeOneBookImportService service;
    @Autowired ContentAssetRepository materials;
    @Autowired MongoTemplate mongo;
    @Test void persistedImportsCopyOnceRemainPrivateAndSeparateTeachers() throws Exception {
        assertEquals(DATABASE,mongo.getDb().getName());
        try {
            Files.createDirectories(DIRECTORY.resolve("source")); var book=GradeOneBookImportService.BOOKS.get(0);
            Path original=DIRECTORY.resolve("source").resolve(book.filename());Files.writeString(original,"%PDF-1.7 persisted book");
            var result=service.importBooks("teacher-one",List.of(book)); assertEquals("IMPORTED",result.get(0).status());
            assertEquals("SKIPPED",service.importBooks("teacher-one",List.of(book)).get(0).status());
            assertEquals(1,materials.count());try(var paths=Files.list(DIRECTORY.resolve("stored"))){assertEquals(1,paths.count());}
            var asset=materials.findByIdAndOwnerId(result.get(0).material().id(),"teacher-one").orElseThrow();
            assertEquals("LIBRARY",asset.getAssignmentMode());assertEquals("TEACHER_ONLY",asset.getVisibility());assertTrue(asset.getAssignedStudentIds().isEmpty());assertEquals("Grade 1",asset.getGrade());
            assertEquals(0,mongo.getCollection("student_assignments").countDocuments());
            assertTrue(materials.findByIdAndOwnerId(asset.getId(),"teacher-two").isEmpty());
            assertEquals("IMPORTED",service.importBooks("teacher-two",List.of(book)).get(0).status());assertEquals(2,materials.count());
            assertEquals("%PDF-1.7 persisted book",Files.readString(original));
        } finally {
            mongo.getDb().drop();
            if(Files.exists(DIRECTORY))try(var paths=Files.walk(DIRECTORY)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
