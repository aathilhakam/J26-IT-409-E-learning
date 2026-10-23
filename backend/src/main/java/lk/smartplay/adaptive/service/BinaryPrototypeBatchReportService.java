package lk.smartplay.adaptive.service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import lk.smartplay.adaptive.dto.BinaryPrototypeBatchReportRequest;

@Service
public class BinaryPrototypeBatchReportService {
    private static final List<String> HEADERS = List.of("Student ID", "Student Name", "Grade", "Lesson ID", "Worksheet ID",
            "Model name", "Model version", "Predicted class", "Needs Practice probability", "Successful probability",
            "Prediction timestamp", "Prediction status", "Missing or invalid input fields / details");

    public byte[] create(BinaryPrototypeBatchReportRequest request) {
        if (request == null || request.rows() == null || request.rows().isEmpty() || request.rows().size() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide 1–500 batch result rows.");
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Batch Predictions");
            Row header = sheet.createRow(0);
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex()); headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex()); headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setWrapText(true);
            for (int i = 0; i < HEADERS.size(); i++) { Cell cell = header.createCell(i); cell.setCellValue(HEADERS.get(i)); cell.setCellStyle(headerStyle); }
            CellStyle probability = workbook.createCellStyle(); probability.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));
            int line = 1;
            for (var item : request.rows()) {
                if (item == null) continue;
                Row row = sheet.createRow(line++);
                List<String> values = List.of(s(item.studentId()), s(item.studentName()), s(item.grade()), s(item.lessonId()), s(item.worksheetId()),
                        s(item.modelName()), s(item.modelVersion()), s(item.predictedClass()));
                for (int c = 0; c < values.size(); c++) row.createCell(c).setCellValue(values.get(c));
                number(row, 8, item.needsPracticeProbability(), probability);
                number(row, 9, item.successfulProbability(), probability);
                row.createCell(10).setCellValue(item.predictedAt() == null ? "" : item.predictedAt().toString());
                row.createCell(11).setCellValue(s(item.status()));
                row.createCell(12).setCellValue(s(item.details()));
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, line - 1), 0, HEADERS.size() - 1));
            int[] widths = {24, 28, 14, 34, 34, 34, 28, 22, 29, 28, 31, 34, 80};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            Sheet notes = workbook.createSheet("Model Notes");
            notes.createRow(0).createCell(0).setCellValue("Result type"); notes.getRow(0).createCell(1).setCellValue("Experimental prototype predictions; not teacher-verified student outcomes.");
            notes.createRow(1).createCell(0).setCellValue("Model"); notes.getRow(1).createCell(1).setCellValue("Existing 10-feature binary Random Forest prototype");
            notes.createRow(2).createCell(0).setCellValue("Supported classes"); notes.getRow(2).createCell(1).setCellValue("Needs Practice, Successful");
            notes.createRow(3).createCell(0).setCellValue("Probability source"); notes.getRow(3).createCell(1).setCellValue("Returned by the authenticated prototype prediction endpoint; class ordering follows the model response.");
            notes.setColumnWidth(0, 28 * 256); notes.setColumnWidth(1, 110 * 256);
            workbook.write(output); return output.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not generate the prototype prediction workbook.", e);
        }
    }
    private static void number(Row row, int column, Double value, CellStyle style) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) return;
        Cell cell = row.createCell(column); cell.setCellValue(value); cell.setCellStyle(style);
    }
    private static String s(String value) { return value == null ? "" : value; }
}
