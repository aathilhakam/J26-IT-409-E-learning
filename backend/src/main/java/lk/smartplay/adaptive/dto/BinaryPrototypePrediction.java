package lk.smartplay.adaptive.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BinaryPrototypePrediction(String predictedClass, Map<String, Double> probabilities,
        String modelVersion, Instant predictedAt, int featureCount, Map<String, Object> features,
        List<String> sourceRecordIds, String status, String warning) {}
