package lk.smartplay.adaptive.service;

import java.util.Map;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AIRecommendationClient {

    private final RestClient restClient;
    private final String inferenceToken;

    public AIRecommendationClient(String baseUrl) { this(baseUrl, ""); }

    @org.springframework.beans.factory.annotation.Autowired
    public AIRecommendationClient(
            @Value("${smartplay.ai.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${smartplay.ai.inference-token:${SMARTPLAY_INFERENCE_TOKEN:}}") String inferenceToken) {
        this.inferenceToken=inferenceToken;
        var requestFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                        .connectTimeout(java.time.Duration.ofSeconds(5)).build());
        requestFactory.setReadTimeout(java.time.Duration.ofSeconds(30));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .build();
    }

    public void requireAuthenticatedInference() {
        if(inferenceToken.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure the same SMARTPLAY_INFERENCE_TOKEN on backend and Python for automatic recommendations");
    }
    public lk.smartplay.adaptive.dto.LivePathwayResponse selectPathway(Map<String, Object> profile) {
        try {
            var call = restClient.post().uri("/api/pathways").contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if(!inferenceToken.isBlank())call.header("Authorization","Bearer "+inferenceToken);
            var result = call.body(profile).retrieve()
                    .body(lk.smartplay.adaptive.dto.LivePathwayResponse.class);
            if(result!=null && result.pathwayType()==null)result=new lk.smartplay.adaptive.dto.LivePathwayResponse(
                result.outcome(),result.probabilities(),result.modelVersion(),result.status(),result.activities(),
                "Needs Practice".equals(result.outcome())?"PERSONALIZED":"COMMON",result.unmatchedNeeds());
            if (result == null || result.outcome() == null || result.probabilities() == null
                    || result.activities() == null || result.modelVersion() == null
                    || !java.util.Set.of("Successful", "Needs Practice", "Waiting").contains(result.outcome())
                    || !java.util.Set.of("PERSONALIZED", "COMMON", "NONE").contains(result.pathwayType())
                    || !result.probabilities().keySet().equals(java.util.Set.of("Successful", "Needs Practice", "Waiting"))
                    || result.probabilities().values().stream().anyMatch(value -> value == null || !Double.isFinite(value) || value < 0 || value > 1)
                    || Math.abs(result.probabilities().values().stream().mapToDouble(Double::doubleValue).sum() - 1) > 0.001) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI service returned an invalid prediction");
            }
            return result;
        } catch (org.springframework.web.client.HttpClientErrorException.UnprocessableEntity exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "AI profile is incomplete or incompatible with the trained model", exception);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to retrieve live pathway from AI service", exception);
        }
    }

    public lk.smartplay.adaptive.dto.BinaryPrototypePrediction predictBinaryPrototype(Map<String, Object> features,
            java.util.List<String> sourceRecordIds) {
        try {
            var call = restClient.post().uri("/api/prototype/predict").contentType(org.springframework.http.MediaType.APPLICATION_JSON);
            if (!inferenceToken.isBlank()) call.header("Authorization", "Bearer " + inferenceToken);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = call.body(Map.of("features", features)).retrieve().body(Map.class);
            if (result == null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prototype service returned an empty prediction");
            Object rawProbabilities = result.get("probabilities");
            Map<String, Double> probabilities = new java.util.LinkedHashMap<>();
            if (rawProbabilities instanceof Map<?, ?> values) values.forEach((key, value) -> {
                if (key instanceof String name && value instanceof Number number) probabilities.put(name, number.doubleValue());
            });
            String predictedClass = result.get("predictedClass") instanceof String value ? value : null;
            String modelVersion = result.get("modelVersion") instanceof String value ? value : null;
            String warning = result.get("warning") instanceof String value ? value : null;
            String status = result.get("status") instanceof String value ? value : null;
            int featureCount = result.get("featureCount") instanceof Number value ? value.intValue() : -1;
            java.time.Instant predictedAt = result.get("predictedAt") instanceof String value ? java.time.Instant.parse(value) : null;
            if (predictedClass == null || !java.util.Set.of("Needs Practice", "Successful").contains(predictedClass)
                    || !probabilities.keySet().equals(java.util.Set.of("Needs Practice", "Successful"))
                    || probabilities.values().stream().anyMatch(value -> !Double.isFinite(value) || value < 0 || value > 1)
                    || Math.abs(probabilities.values().stream().mapToDouble(Double::doubleValue).sum() - 1.0) > 1e-9
                    || modelVersion == null || predictedAt == null || featureCount != 10
                    || !"EXPLORATORY_PROTOTYPE".equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Prototype service returned an invalid two-class prediction");
            }
            return new lk.smartplay.adaptive.dto.BinaryPrototypePrediction(predictedClass, Map.copyOf(probabilities),
                    modelVersion, predictedAt, featureCount, Map.copyOf(features), List.copyOf(sourceRecordIds), status, warning);
        } catch (org.springframework.web.client.HttpClientErrorException.UnprocessableEntity exception) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Prototype prediction is unavailable because required features are missing or invalid", exception);
        } catch (RestClientException | java.time.format.DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to retrieve a valid binary prototype prediction", exception);
        }
    }

    public Map<String, Object> getRecommendation(String datasetStudentId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = restClient.get()
                    .uri("/api/recommendations/{studentId}", datasetStudentId)
                    .retrieve()
                    .body(Map.class);

            if (result == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "AI service returned an empty response");
            }

            return result;

        } catch (RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Unable to retrieve recommendation from AI service",
                    ex);
        }
    }
}
