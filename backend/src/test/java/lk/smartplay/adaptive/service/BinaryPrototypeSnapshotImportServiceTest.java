package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import lk.smartplay.adaptive.model.BinaryPrototypeFeatureSnapshot;
import lk.smartplay.adaptive.model.ContentAsset;
import lk.smartplay.adaptive.model.Student;
import lk.smartplay.adaptive.repository.BinaryPrototypeFeatureSnapshotRepository;
import lk.smartplay.adaptive.repository.ContentAssetRepository;
import lk.smartplay.adaptive.repository.StudentRepository;

class BinaryPrototypeSnapshotImportServiceTest {
    private Student student;
    private BinaryPrototypeFeatureSnapshotRepository snapshots;
    private BinaryPrototypeSnapshotImportService service;

    @BeforeEach void setUp() {
        student = mock(Student.class);
        when(student.getId()).thenReturn("stable-student-id");
        when(student.getName()).thenReturn("Amaya Silva");
        when(student.getGrade()).thenReturn("Grade 1");
        when(student.isActive()).thenReturn(true);
        StudentRepository roster = mock(StudentRepository.class);
        when(roster.findByOwnerId("teacher-1")).thenReturn(List.of(student));
        snapshots = mock(BinaryPrototypeFeatureSnapshotRepository.class);
        when(snapshots.findByStudentIdAndOwnerIdAndFingerprint(eq("stable-student-id"), eq("teacher-1"), any()))
                .thenReturn(Optional.empty());
        ContentAssetRepository materials = mock(ContentAssetRepository.class);
        when(materials.findByIdAndOwnerId("material-1", "teacher-1"))
                .thenReturn(Optional.of(mock(ContentAsset.class)));
        service = new BinaryPrototypeSnapshotImportService(roster, snapshots, materials);
    }

    @Test void downloadPreviewImportAndStoredSnapshotUseStableStudentAndSelectedWorksheetIds() throws Exception {
        byte[] template = service.template("teacher-1", "Grade 1", "material-1");
        byte[] completed = fillTemplate(template);
        var upload = upload(completed);

        var preview = service.preview("teacher-1", upload);
        assertEquals(1, preview.valid());
        assertEquals("stable-student-id", preview.rows().get(0).studentId());

        var result = service.importFile("teacher-1", upload, preview.token());
        assertEquals(1, result.imported());
        ArgumentCaptor<BinaryPrototypeFeatureSnapshot> saved = ArgumentCaptor.forClass(BinaryPrototypeFeatureSnapshot.class);
        verify(snapshots).save(saved.capture());
        assertEquals("stable-student-id", saved.getValue().getStudentId());
        assertEquals("teacher-1", saved.getValue().getOwnerId());
        assertEquals("Amaya Silva", saved.getValue().getStudentName());
        assertEquals(6, saved.getValue().getFeatures().size());
        assertEquals(3L, saved.getValue().getFeatures().get("Total_Activities"));
        assertEquals("Six numeric template values saved. Four observation features are read from saved teacher feedback when prediction runs.",
                result.rows().get(0).message());
    }

    @Test void exactDuplicateIsReportedAndNotSavedTwice() throws Exception {
        var upload = upload(fillTemplate(service.template("teacher-1", "Grade 1", "material-1")));
        var preview = service.preview("teacher-1", upload);
        when(snapshots.findByStudentIdAndOwnerIdAndFingerprint(eq("stable-student-id"), eq("teacher-1"), any()))
                .thenReturn(Optional.of(mock(BinaryPrototypeFeatureSnapshot.class)));

        var existing = service.preview("teacher-1", upload);
        assertEquals(1, existing.existing());
        var result = service.importFile("teacher-1", upload, existing.token());
        assertEquals(1, result.existing());
        verify(snapshots, never()).save(any(BinaryPrototypeFeatureSnapshot.class));
        assertNotNull(preview.token());
    }

    private static byte[] fillTemplate(byte[] template) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(template));
                var output = new ByteArrayOutputStream()) {
            var row = workbook.getSheet("Prototype Features").getRow(1);
            row.createCell(4).setCellValue(1.5);
            row.createCell(5).setCellValue(3);
            row.createCell(6).setCellValue(80);
            row.createCell(7).setCellValue(4);
            row.createCell(8).setCellValue(3);
            row.createCell(9).setCellValue(75);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "prototype.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }
}
