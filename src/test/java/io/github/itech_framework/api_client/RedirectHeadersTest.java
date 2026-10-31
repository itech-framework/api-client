package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.ApiKey;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.Header;
import io.github.itech_framework.api_client.utils.enums.AuthType;

public class RedirectHeadersTest extends BaseClientTest {

    @Authenticated(AuthType.API_KEY)
    @ApiKey(name = "X-API-Key", value = "secret-key")
    public interface KeyApi {
        @GET("/start")
        @Header(name = "Accept", value = "application/json")
        String start();
    }

    @Test
    void customAuthHeadersAreNotSentToAnotherHostAfterARedirect() throws Exception {
        TestServer other = server();
        other.route("/target", (request, call) -> Response.of(200, "ok"));
        TestServer origin = server();
        origin.route("/start", (request, call) -> Response.empty(302).withHeader("Location", other.url() + "/target"));
        KeyApi api = client(KeyApi.class, config(origin).build());

        assertEquals("ok", api.start());

        assertNull(other.requestsTo("/target").get(0).header("X-API-Key"));
    }

    @Test
    void harmlessHeadersSurviveACrossHostRedirect() throws Exception {
        TestServer other = server();
        other.route("/target", (request, call) -> Response.of(200, "ok"));
        TestServer origin = server();
        origin.route("/start", (request, call) -> Response.empty(302).withHeader("Location", other.url() + "/target"));
        KeyApi api = client(KeyApi.class, config(origin).build());

        api.start();

        assertEquals("application/json", other.requestsTo("/target").get(0).header("Accept"));
    }

    @Test
    void customAuthHeadersAreKeptWhenTheRedirectStaysOnTheSameHost() throws Exception {
        TestServer origin = server();
        origin.route("/start", (request, call) -> Response.empty(302).withHeader("Location", origin.url() + "/final"));
        origin.route("/final", (request, call) -> Response.of(200, "ok"));
        KeyApi api = client(KeyApi.class, config(origin).build());

        api.start();

        assertEquals("secret-key", origin.requestsTo("/final").get(0).header("X-API-Key"));
    }
}