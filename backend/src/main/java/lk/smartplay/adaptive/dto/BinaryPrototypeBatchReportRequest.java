package lk.smartplay.adaptive.dto;

import java.time.Instant;
import java.util.List;

public record BinaryPrototypeBatchReportRequest(List<Row> rows) {
    public record Row(String studentId, String studentName, String grade, String lessonId, String worksheetId,
            String modelName, String modelVersion, String predictedClass, Double needsPracticeProbability,
            Double successfulProbability, Instant predictedAt, String status, String details) {}
}
