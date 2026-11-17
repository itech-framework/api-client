package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BasicAuth;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.utils.enums.AuthType;
import io.github.itech_framework.api_client.utils.response.ApiExchange;

public class ApiExchangeTypeTest extends BaseClientTest {

    @Authenticated(AuthType.BASIC)
    @BasicAuth(username = "user", password = "pass")
    public interface ExchangeApi {
        @GET("/echo")
        ApiExchange<String> echo();

        @GET("/flaky")
        ApiExchange<String> flaky();

        @GET("/missing")
        ApiExchange<String> missing();

        @GET("/echo")
        CompletableFuture<ApiExchange<String>> echoAsync();
    }

    @Test
    void exchangeCarriesResponseRequestTimingAndAttempts() throws Exception {
        TestServer server = server();
        server.route("/echo", (request, call) -> Response.of(200, "ok"));

        ApiExchange<String> exchange = client(ExchangeApi.class, config(server).build()).echo();

        assertEquals(200, exchange.status());
        assertEquals("ok", exchange.body());
        assertNotNull(exchange.request());
        assertEquals("GET", exchange.request().method());
        String expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, exchange.request().header("Authorization").orElseThrow());
        assertEquals(1, exchange.attempts());
        assertTrue(exchange.timing().headersMillis() >= 0);
        assertTrue(exchange.timing().totalMillis() >= exchange.timing().headersMillis());
        assertTrue(exchange.redirects().isEmpty());
    }

    @Test
    void retriedCallReportsAttempts() throws Exception {
        TestServer server = server();
        server.route("/flaky", (request, call) -> call == 1 ? Response.empty(503) : Response.of(200, "ok"));

        ApiExchange<String> exchange = client(ExchangeApi.class, config(server).build()).flaky();

        assertEquals(2, server.requestsTo("/flaky").size());
        assertEquals(2, exchange.attempts());
        assertEquals(200, exchange.status());
    }

    @Test
    void errorStatusStillThrowsByDefault() throws Exception {
        TestServer server = server();
        server.route("/missing", (request, call) -> Response.of(404, "nope"));

        ExchangeApi api = client(ExchangeApi.class, config(server).build());

        assertThrows(ApiException.class, api::missing);
    }

    @Test
    void errorStatusReturnsExchangeWhenNotThrowing() throws Exception {
        TestServer server = server();
        server.route("/missing", (request, call) -> Response.of(404, "nope"));

        ApiExchange<String> exchange = client(ExchangeApi.class, config(server).throwOnError(false).build()).missing();

        assertEquals(404, exchange.status());
        assertEquals("nope", exchange.body());
        assertNotNull(exchange.request());
    }

    @Test
    void asyncCallReturnsExchange() throws Exception {
        TestServer server = server();
        server.route("/echo", (request, call) -> Response.of(200, "ok"));

        ApiExchange<String> exchange = client(ExchangeApi.class, config(server).build()).echoAsync().get();

        assertEquals("ok", exchange.body());
        assertNotNull(exchange.request());
    }
}