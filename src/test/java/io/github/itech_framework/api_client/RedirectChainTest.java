package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.utils.response.ApiExchange;
import io.github.itech_framework.api_client.utils.response.RedirectHop;

public class RedirectChainTest extends BaseClientTest {

    public interface RedirectApi {
        @GET("/a")
        ApiExchange<String> start();
    }

    @Test
    void exchangeListsEveryRedirectHop() throws Exception {
        TestServer server = server();
        server.route("/a", (request, call) -> Response.empty(302).withHeader("Location", server.url() + "/b"));
        server.route("/b", (request, call) -> Response.empty(301).withHeader("Location", server.url() + "/c"));
        server.route("/c", (request, call) -> Response.of(200, "done"));

        ApiExchange<String> exchange = client(RedirectApi.class, config(server).build()).start();

        assertEquals(200, exchange.status());
        assertEquals("done", exchange.body());
        assertEquals(2, exchange.redirects().size());

        RedirectHop first = exchange.redirects().get(0);
        assertEquals(302, first.status());
        assertEquals(server.url() + "/a", first.from());
        assertEquals(server.url() + "/b", first.location());

        RedirectHop second = exchange.redirects().get(1);
        assertEquals(301, second.status());
        assertEquals(server.url() + "/b", second.from());
        assertEquals(server.url() + "/c", second.location());

        assertEquals(server.url() + "/c", exchange.request().uri());
    }

    @Test
    void noRedirectsGivesEmptyList() throws Exception {
        TestServer server = server();
        server.route("/a", (request, call) -> Response.of(200, "ok"));

        ApiExchange<String> exchange = client(RedirectApi.class, config(server).build()).start();

        assertTrue(exchange.redirects().isEmpty());
    }

    @Test
    void notFollowingReturnsTheRedirectItself() throws Exception {
        TestServer server = server();
        server.route("/a", (request, call) -> Response.empty(302).withHeader("Location", server.url() + "/b"));

        ApiExchange<String> exchange = client(RedirectApi.class,
                config(server).followRedirects(false).build()).start();

        assertEquals(302, exchange.status());
        assertEquals(server.url() + "/b", exchange.response().header("Location").orElseThrow());
        assertTrue(exchange.redirects().isEmpty());
    }
}