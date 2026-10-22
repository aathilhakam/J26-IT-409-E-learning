package lk.smartplay.adaptive.service;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;

class AIRecommendationClientTest {
    HttpServer server;
    AIRecommendationClient client;
    int status = 200;
    String payload;
    String receivedAuthorization;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/pathways", exchange -> {
            receivedAuthorization=exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); client = new AIRecommendationClient("http://127.0.0.1:" + server.getAddress().getPort());
        payload = """
          {"Predicted_Outcome":"Waiting","Prediction_Probabilities":{"Successful":0.1,"Needs Practice":0.2,"Waiting":0.7},
           "Model_Version":"hash","Status":"No suitable validated activity","Learning_Path":[]}
          """;
    }
    @AfterEach void close() { server.stop(0); }
    @Test void validPredictionAndPythonErrorsAreHandled() {
        assertEquals("Waiting", client.selectPathway(Map.of()).outcome());
        status = 422;
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> client.selectPathway(Map.of())).getStatusCode().value());
        status = 500;
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> client.selectPathway(Map.of())).getStatusCode().value());
        status = 200; payload = "{}";
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> client.selectPathway(Map.of())).getStatusCode().value());
    }
    @Test void unavailableServiceIsExplicit() {
        server.stop(0);
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> client.selectPathway(Map.of())).getStatusCode().value());
    }
    @Test void configuredBackendInferenceTokenIsSentOnlyServerSide() {
        client=new AIRecommendationClient("http://127.0.0.1:"+server.getAddress().getPort(),"isolated-test-token");
        client.selectPathway(Map.of());assertEquals("Bearer isolated-test-token",receivedAuthorization);
    }
    @Test void automaticInferenceRequiresConfiguredCredentialsRatherThanSendingUnauthenticatedRequests() {
        assertEquals(503,assertThrows(ResponseStatusException.class,()->client.requireAuthenticatedInference()).getStatusCode().value());
    }
}
