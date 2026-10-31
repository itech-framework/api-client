package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BearerToken;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.utils.enums.AuthType;

public class RedirectCredentialsTest extends BaseClientTest {

    @Authenticated(AuthType.BEARER)
    @BearerToken(token = "secret-token")
    public interface SecureRedirectApi {
        @GET("/start")
        String start();

        @GET("/same")
        String same();
    }

    @Test
    @DisplayName("Authorization is NOT sent to another host after a redirect (fails until SafeRedirectStrategy is used for every client)")
    void authorizationIsNotSentToAnotherHostAfterARedirect() throws Exception {
        TestServer other = server();
        other.route("/target", (request, call) -> Response.of(200, "ok"));
        TestServer origin = server();
        origin.route("/start", (request, call) -> Response.empty(302).withHeader("Location", other.url() + "/target"));
        SecureRedirectApi api = client(SecureRedirectApi.class, config(origin).build());

        assertEquals("ok", api.start());

        assertEquals(1, other.requestsTo("/target").size());
        assertNull(other.requestsTo("/target").get(0).header("Authorization"));
    }

    @Test
    void authorizationIsKeptWhenTheRedirectStaysOnTheSameHost() throws Exception {
        TestServer origin = server();
        origin.route("/same", (request, call) -> Response.empty(302).withHeader("Location", origin.url() + "/final"));
        origin.route("/final", (request, call) -> Response.of(200, "ok"));
        SecureRedirectApi api = client(SecureRedirectApi.class, config(origin).build());

        assertEquals("ok", api.same());

        assertEquals("Bearer secret-token", origin.requestsTo("/final").get(0).header("Authorization"));
    }
}