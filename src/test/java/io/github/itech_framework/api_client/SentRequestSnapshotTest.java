package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BasicAuth;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.utils.enums.AuthType;
import io.github.itech_framework.api_client.utils.response.SentRequest;

public class SentRequestSnapshotTest extends BaseClientTest {

    public static final class Capture implements Interceptor {
        final List<SentRequest> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onResponse(HttpResponse response, HttpContext context) {
            seen.add((SentRequest) context.getAttribute(Interceptor.SENT_REQUEST));
        }
    }

    @Authenticated(AuthType.BASIC)
    @BasicAuth(username = "user", password = "pass")
    public interface EchoApi {
        @GET("/echo")
        String echo();

        @GET("/flaky")
        String flaky();
    }

    @Test
    void snapshotHasApacheAndAuthHeaders() throws Exception {
        TestServer server = server();
        server.route("/echo", (request, call) -> Response.of(200, "ok"));
        Capture capture = new Capture();

        client(EchoApi.class, config(server).addInterceptor(capture).build()).echo();

        assertEquals(1, capture.seen.size());
        SentRequest sent = capture.seen.get(0);
        assertNotNull(sent);
        assertEquals("GET", sent.method());
        assertEquals(server.url() + "/echo", sent.uri());
        assertTrue(sent.header("Host").isPresent());
        assertTrue(sent.header("User-Agent").isPresent());
        String expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, sent.header("Authorization").orElseThrow());
    }

    @Test
    void snapshotComesFromTheFinalAttempt() throws Exception {
        TestServer server = server();
        server.route("/flaky", (request, call) -> call == 1 ? Response.empty(503) : Response.of(200, "ok"));
        Capture capture = new Capture();

        client(EchoApi.class, config(server).addInterceptor(capture).build()).flaky();

        assertEquals(2, server.requestsTo("/flaky").size());
        assertEquals(1, capture.seen.size());
        assertNotNull(capture.seen.get(0));
    }

    @Test
    void toStringHidesSecrets() {
        SentRequest sent = new SentRequest("GET", "http://x/a",
                Map.of("Authorization", List.of("Bearer secret123")));

        assertFalse(sent.toString().contains("secret123"));
        assertTrue(sent.toString().contains("Bearer ***"));
    }
}