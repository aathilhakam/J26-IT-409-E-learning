package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import lk.smartplay.adaptive.dto.BinaryPrototypeBatchReportRequest;

class BinaryPrototypeBatchReportServiceTest {
    @Test void exportsActualPredictionFieldsAndLeavesBlockedProbabilitiesBlank() throws Exception {
        var service = new BinaryPrototypeBatchReportService();
        var predicted = new BinaryPrototypeBatchReportRequest.Row("s1", "A Student", "Grade 1", "lesson-1", "lesson-1",
                "10-feature binary Random Forest prototype", "sha256-v1", "Successful", 0.12, 0.88,
                Instant.parse("2026-10-23T08:00:00Z"), "PREDICTED", "");
        var blocked = new BinaryPrototypeBatchReportRequest.Row("s2", "B Student", "Grade 1", "lesson-1", "lesson-1",
                "10-feature binary Random Forest prototype", "", "", null, null, null, "BLOCKED", "No submitted lesson feedback.");
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(service.create(new BinaryPrototypeBatchReportRequest(List.of(predicted, blocked)))))) {
            var sheet = workbook.getSheet("Batch Predictions");
            assertEquals("Student ID", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Needs Practice probability", sheet.getRow(0).getCell(8).getStringCellValue());
            assertEquals("Prediction status", sheet.getRow(0).getCell(11).getStringCellValue());
            assertEquals("Successful", sheet.getRow(1).getCell(7).getStringCellValue());
            assertEquals(0.12, sheet.getRow(1).getCell(8).getNumericCellValue(), 1e-9);
            assertEquals(0.88, sheet.getRow(1).getCell(9).getNumericCellValue(), 1e-9);
            assertEquals("2026-10-23T08:00:00Z", sheet.getRow(1).getCell(10).getStringCellValue());
            assertEquals("BLOCKED", sheet.getRow(2).getCell(11).getStringCellValue());
            assertEquals("No submitted lesson feedback.", sheet.getRow(2).getCell(12).getStringCellValue());
            assertNull(sheet.getRow(2).getCell(8));
            assertNotNull(sheet.getPaneInformation());
            assertEquals(1, sheet.getPaneInformation().getHorizontalSplitPosition());
            assertTrue(workbook.getSheet("Model Notes").getRow(0).getCell(1).getStringCellValue().contains("not teacher-verified"));
        }
    }
}
