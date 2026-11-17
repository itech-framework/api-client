package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.parameters.Headers;
import io.github.itech_framework.api_client.utils.response.ApiResponse;

public class CookieBehaviorTest extends BaseClientTest {

    public interface SessionApi {
        @GET("/login")
        String login();

        @GET("/login")
        ApiResponse<String> loginRaw();

        @GET("/me")
        String me();

        @GET("/me")
        String meWith(@Headers Map<String, String> headers);
    }

    private TestServer sessionServer() throws Exception {
        TestServer server = server();
        server.route("/login", (request, call) -> Response.of(200, "in").withHeader("Set-Cookie", "session=abc; Path=/"));
        server.route("/me", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    @Test
    void cookiesAreOffByDefault() throws Exception {
        TestServer server = sessionServer();

        SessionApi api = client(SessionApi.class, config(server).build());
        api.login();
        api.me();

        assertNull(server.requestsTo("/me").get(0).header("Cookie"));
    }

    @Test
    void cookiesWorkWhenEnabled() throws Exception {
        TestServer server = sessionServer();

        SessionApi api = client(SessionApi.class, config(server).cookiesEnabled(true).build());
        api.login();
        api.me();

        assertEquals("session=abc", server.requestsTo("/me").get(0).header("Cookie"));
    }

    @Test
    void typedCookieHeaderIsStillSentWhenCookiesAreOff() throws Exception {
        TestServer server = sessionServer();

        SessionApi api = client(SessionApi.class, config(server).build());
        api.meWith(Map.of("Cookie", "manual=1"));

        assertEquals("manual=1", server.requestsTo("/me").get(0).header("Cookie"));
    }

    @Test
    void setCookieHeaderIsStillReadableWhenCookiesAreOff() throws Exception {
        TestServer server = sessionServer();

        ApiResponse<String> response = client(SessionApi.class, config(server).build()).loginRaw();

        assertTrue(response.header("Set-Cookie").orElseThrow().startsWith("session=abc"));
    }
}