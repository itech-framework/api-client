package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.NotFoundException;
import io.github.itech_framework.api_client.utils.response.StreamResponse;

public class StreamResponseTest extends BaseClientTest {

    public interface StreamApi {
        @GET("/big")
        StreamResponse big();

        @GET("/big")
        CompletableFuture<StreamResponse> bigAsync();

        @GET("/gone")
        StreamResponse gone();
    }

    @Test
    void streamResponseDoesNotReadTheBodyIntoMemory() throws Exception {
        TestServer server = server();
        server.route("/big", (request, call) -> Response.of(200, "x".repeat(100_000)).withHeader("X-Kind", "blob"));
        StreamApi api = client(StreamApi.class, config(server).build());

        try (StreamResponse response = api.big()) {
            assertEquals(200, response.status());
            assertEquals("blob", response.header("x-kind").orElseThrow());
            assertEquals(100_000, response.contentLength());
            assertEquals(100_000, response.inputStream().readAllBytes().length);
        }
    }

    @Test
    void streamResponseWorksInsideACompletableFuture() throws Exception {
        TestServer server = server();
        server.route("/big", (request, call) -> Response.of(200, "y".repeat(1_000)));
        StreamApi api = client(StreamApi.class, config(server).build());

        try (StreamResponse response = api.bigAsync().get(5, TimeUnit.SECONDS)) {
            assertEquals(1_000, response.inputStream().readAllBytes().length);
        }
    }

    @Test
    void streamResponseStillThrowsForErrorStatuses() throws Exception {
        TestServer server = server();
        StreamApi api = client(StreamApi.class, config(server).build());

        assertThrows(NotFoundException.class, api::gone);
    }
}