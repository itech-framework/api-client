package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.utils.response.ApiResponse;

public class RedirectPropertiesTest extends DynamicApiTest {

    @ApiClient
    public interface RedirectApi {
        @GET("/go")
        ApiResponse<Void> go();
    }

    @Test
    void dottedFollowRedirectsKeyTurnsRedirectsOff() throws Exception {
        TestServer server = server();
        server.route("/go", (request, call) -> Response.empty(302).withHeader("Location", "/landed"));
        server.route("/landed", (request, call) -> Response.of(200, "landed"));
        useProperties("api.client.redirectapi.base.url", server.url(), "api.client.redirectapi.follow.redirects",
                "false");

        assertEquals(302, processed(RedirectApi.class).go().status());
    }

    @Test
    void legacyFollowRedirectsKeyStillWorks() throws Exception {
        TestServer server = server();
        server.route("/go", (request, call) -> Response.empty(302).withHeader("Location", "/landed"));
        server.route("/landed", (request, call) -> Response.of(200, "landed"));
        useProperties("api.client.redirectapi.base.url", server.url(), "api.client.redirectapi.followRedirects",
                "false");

        assertEquals(302, processed(RedirectApi.class).go().status());
    }

    @Test
    void redirectsAreFollowedByDefault() throws Exception {
        TestServer server = server();
        server.route("/go", (request, call) -> Response.empty(302).withHeader("Location", "/landed"));
        server.route("/landed", (request, call) -> Response.of(200, "landed"));
        useProperties("api.client.redirectapi.base.url", server.url());

        assertEquals(200, processed(RedirectApi.class).go().status());
    }
}