package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.utils.response.ApiResponse;
import io.github.itech_framework.api_client.utils.response.StreamResponse;

public class BodyLimitAndSaveTest extends BaseClientTest {

    private static final String TWENTY = "01234567890123456789";

    public interface LimitApi {
        @GET("/big")
        String big();

        @GET("/big")
        ApiResponse<String> bigResponse();

        @GET("/big")
        StreamResponse bigStream();

        @GET("/exact")
        String exact();

        @GET("/huge")
        String huge();

        @GET("/fail-big")
        String failBig();

        @GET("/fail-small")
        String failSmall();

        @GET("/empty")
        StreamResponse empty();
    }

    private TestServer limitServer() throws Exception {
        TestServer server = server();
        server.route("/big", (request, call) -> Response.of(200, TWENTY));
        server.route("/exact", (request, call) -> Response.of(200, "0123456789"));
        server.route("/huge", (request, call) -> Response.of(200, "x".repeat(1000)));
        server.route("/fail-big", (request, call) -> Response.of(500, TWENTY));
        server.route("/fail-small", (request, call) -> Response.of(500, "boom"));
        server.route("/empty", (request, call) -> Response.empty(204));
        return server;
    }

    private LimitApi limited(TestServer server) {
        return client(LimitApi.class, config(server).maxRetries(0).maxBodyBytes(10).build());
    }

    @Test
    void successBodyOverTheLimitThrowsBodyTooLarge() throws Exception {
        TestServer server = limitServer();

        ApiException error = assertThrows(ApiException.class, () -> limited(server).big());

        assertEquals("BODY_TOO_LARGE", error.getErrorCode());
        assertEquals(200, error.getStatusCode());
        assertEquals("20", error.header("content-length").orElseThrow());
    }

    @Test
    void apiResponseBodyOverTheLimitThrowsBodyTooLarge() throws Exception {
        TestServer server = limitServer();

        ApiException error = assertThrows(ApiException.class, () -> limited(server).bigResponse());

        assertEquals("BODY_TOO_LARGE", error.getErrorCode());
    }

    @Test
    void successBodyExactlyAtTheLimitIsReturned() throws Exception {
        TestServer server = limitServer();

        assertEquals("0123456789", limited(server).exact());
    }

    @Test
    void noLimitByDefault() throws Exception {
        TestServer server = limitServer();
        LimitApi api = client(LimitApi.class, config(server).maxRetries(0).build());

        assertEquals(1000, api.huge().length());
    }

    @Test
    void errorBodyOverTheLimitIsTruncated() throws Exception {
        TestServer server = limitServer();

        ApiException error = assertThrows(ApiException.class, () -> limited(server).failBig());

        assertEquals(500, error.getStatusCode());
        assertTrue(error.isBodyTruncated());
        assertEquals(10, error.getResponseBytes().length);
        assertEquals("0123456789", error.getResponseBody());
    }

    @Test
    void errorBodyUnderTheLimitIsNotTruncated() throws Exception {
        TestServer server = limitServer();

        ApiException error = assertThrows(ApiException.class, () -> limited(server).failSmall());

        assertFalse(error.isBodyTruncated());
        assertEquals(4, error.getResponseBytes().length);
        assertEquals("boom", error.getResponseBody());
    }

    @Test
    void streamResponseIsNotLimitedAndSavesTheWholeBody(@TempDir Path dir) throws Exception {
        TestServer server = limitServer();
        Path target = dir.resolve("out.txt");

        try (StreamResponse stream = limited(server).bigStream()) {
            assertEquals(20, stream.saveTo(target));
        }

        assertEquals(TWENTY, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void saveToReplacesAnExistingFile(@TempDir Path dir) throws Exception {
        TestServer server = limitServer();
        Path target = dir.resolve("out.txt");
        Files.writeString(target, "old content that is longer than the new one", StandardCharsets.UTF_8);

        try (StreamResponse stream = limited(server).bigStream()) {
            stream.saveTo(target);
        }

        assertEquals(TWENTY, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void saveToWithAnEmptyBodyCreatesAnEmptyFile(@TempDir Path dir) throws Exception {
        TestServer server = limitServer();
        Path target = dir.resolve("empty.bin");

        try (StreamResponse stream = limited(server).empty()) {
            assertEquals(0, stream.saveTo(target));
        }

        assertEquals(0, Files.size(target));
    }
}