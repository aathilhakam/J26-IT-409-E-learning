package lk.smartplay.adaptive.service;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AIRecommendationClient {

    private final RestClient restClient;

    public AIRecommendationClient(
            @Value("${smartplay.ai.base-url:http://127.0.0.1:8000}") String baseUrl) {

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
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