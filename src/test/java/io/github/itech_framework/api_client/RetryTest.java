package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.methods.PUT;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.exceptions.BadRequestException;
import io.github.itech_framework.api_client.exceptions.ServiceUnavailableException;

public class RetryTest extends BaseClientTest {

    public interface RetryApi {
        @GET("/flaky")
        String flaky();

        @GET("/down")
        String down();

        @GET("/bad")
        String bad();

        @GET("/limited")
        String limited();

        @POST("/post")
        String post(@Body String body);

        @PUT("/put")
        String put(@Body String body);
    }

    @Test
    void retriesIdempotentRequestUntilSuccess() throws Exception {
        TestServer server = server();
        server.route("/flaky", (request, call) -> call < 3 ? Response.empty(503) : Response.of(200, "ok"));
        RetryApi api = client(RetryApi.class, config(server).build());

        assertEquals("ok", api.flaky());
        assertEquals(3, server.requestsTo("/flaky").size());
    }

    @Test
    void givesUpAfterConfiguredRetries() throws Exception {
        TestServer server = server();
        server.route("/down", (request, call) -> Response.empty(503));
        RetryApi api = client(RetryApi.class, config(server).build());

        assertThrows(ServiceUnavailableException.class, api::down);
        assertEquals(3, server.requestsTo("/down").size());
    }

    @Test
    void doesNotRetryWhenRetriesAreDisabled() throws Exception {
        TestServer server = server();
        server.route("/down", (request, call) -> Response.empty(503));
        RetryApi api = client(RetryApi.class, config(server).maxRetries(0).build());

        assertThrows(ServiceUnavailableException.class, api::down);
        assertEquals(1, server.requestsTo("/down").size());
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        TestServer server = server();
        server.route("/bad", (request, call) -> Response.empty(400));
        RetryApi api = client(RetryApi.class, config(server).build());

        assertThrows(BadRequestException.class, api::bad);
        assertEquals(1, server.requestsTo("/bad").size());
    }

    @Test
    void doesNotRetryPost() throws Exception {
        TestServer server = server();
        server.route("/post", (request, call) -> Response.empty(503));
        RetryApi api = client(RetryApi.class, config(server).build());

        assertThrows(ServiceUnavailableException.class, () -> api.post("payload"));
        assertEquals(1, server.requestsTo("/post").size());
    }

    @Test
    void retriesPutWithTheSameBody() throws Exception {
        TestServer server = server();
        server.route("/put", (request, call) -> call < 2 ? Response.empty(503) : Response.of(200, "saved"));
        RetryApi api = client(RetryApi.class, config(server).build());

        assertEquals("saved", api.put("payload"));
        assertEquals(2, server.requestsTo("/put").size());
        assertEquals(server.requestsTo("/put").get(0).body(), server.requestsTo("/put").get(1).body());
    }

    @Test
    void retriesTooManyRequestsHonoringRetryAfter() throws Exception {
        TestServer server = server();
        server.route("/limited", (request, call) -> call == 1
                ? Response.empty(429).withHeader("Retry-After", "0")
                : Response.of(200, "ok"));
        RetryApi api = client(RetryApi.class, config(server).build());

        assertEquals("ok", api.limited());
        assertEquals(2, server.requestsTo("/limited").size());
    }
}