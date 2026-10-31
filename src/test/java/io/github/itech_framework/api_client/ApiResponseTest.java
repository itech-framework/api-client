package io.github.itech_framework.api_client;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.NotFoundException;
import io.github.itech_framework.api_client.utils.response.ApiResponse;

public class ApiResponseTest extends BaseClientTest {

    public static class FileInfo {
        public String id;
        public String name;

        public FileInfo() {
        }
    }

    public interface ResponseApi {
        @GET("/file")
        ApiResponse<FileInfo> file();

        @GET("/text")
        ApiResponse<String> text();

        @GET("/empty")
        ApiResponse<Void> empty();

        @GET("/async-file")
        CompletableFuture<ApiResponse<FileInfo>> fileAsync();

        @GET("/missing")
        ApiResponse<String> missing();

        @GET("/redirect")
        ApiResponse<Void> redirect();
    }

    @Test
    void apiResponseExposesStatusHeadersAndBody() throws Exception {
        TestServer server = server();
        server.route("/file", (request, call) -> Response.json(200, "{\"id\":\"7\",\"name\":\"report\"}")
                .withHeader("X-Total", "5"));
        ResponseApi api = client(ResponseApi.class, config(server).build());

        ApiResponse<FileInfo> response = api.file();

        assertEquals(200, response.status());
        assertTrue(response.isSuccessful());
        assertEquals("report", response.body().name);
        assertEquals("5", response.header("x-total").orElseThrow());
        assertTrue(response.header("X-Missing").isEmpty());
    }

    @Test
    void apiResponseWorksWithStringAndVoidBodies() throws Exception {
        TestServer server = server();
        server.route("/text", (request, call) -> Response.of(200, "hello"));
        server.route("/empty", (request, call) -> Response.empty(204));
        ResponseApi api = client(ResponseApi.class, config(server).build());

        assertEquals("hello", api.text().body());

        ApiResponse<Void> empty = api.empty();
        assertEquals(204, empty.status());
        assertTrue(empty.isSuccessful());
        assertNull(empty.body());
    }

    @Test
    void apiResponseWorksInsideACompletableFuture() throws Exception {
        TestServer server = server();
        server.route("/async-file", (request, call) -> Response.json(200, "{\"id\":\"1\",\"name\":\"async\"}"));
        ResponseApi api = client(ResponseApi.class, config(server).build());

        ApiResponse<FileInfo> response = api.fileAsync().get(5, TimeUnit.SECONDS);

        assertEquals(200, response.status());
        assertEquals("async", response.body().name);
    }

    @Test
    void apiResponseStillThrowsForErrorStatuses() throws Exception {
        TestServer server = server();
        ResponseApi api = client(ResponseApi.class, config(server).build());

        assertThrows(NotFoundException.class, api::missing);
    }

    @Test
    void apiResponseShowsRedirectsWhenFollowingIsOff() throws Exception {
        TestServer server = server();
        server.route("/redirect", (request, call) -> Response.empty(302).withHeader("Location", "/somewhere"));
        ResponseApi api = client(ResponseApi.class, config(server).followRedirects(false).build());

        ApiResponse<Void> response = api.redirect();

        assertEquals(302, response.status());
        assertFalse(response.isSuccessful());
        assertEquals("/somewhere", response.header("Location").orElseThrow());
        assertNull(response.body());
        assertEquals(1, server.requests().size());
    }
}