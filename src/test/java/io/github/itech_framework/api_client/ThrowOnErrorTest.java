package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.NotFoundException;
import io.github.itech_framework.api_client.utils.response.ApiResponse;
import io.github.itech_framework.api_client.utils.response.StreamResponse;

public class ThrowOnErrorTest extends BaseClientTest {

    public static class Item {
        public String name;

        public Item() {
        }
    }

    public interface ErrorModeApi {
        @GET("/missing")
        String missingText();

        @GET("/missing")
        ApiResponse<String> missingResponse();

        @GET("/missing")
        ApiResponse<byte[]> missingBytes();

        @GET("/missing")
        StreamResponse missingStream();

        @GET("/missing")
        ApiResponse<Item> missingItem();
    }

    private TestServer notFoundServer() throws Exception {
        TestServer server = server();
        server.route("/missing", (request, call) -> Response.of(404, "gone").withHeader("X-Reason", "no-such-file"));
        return server;
    }

    @Test
    void exceptionCarriesStatusBodyAndHeaders() throws Exception {
        TestServer server = notFoundServer();
        ErrorModeApi api = client(ErrorModeApi.class, config(server).maxRetries(0).build());

        NotFoundException error = assertThrows(NotFoundException.class, api::missingText);

        assertEquals(404, error.getStatusCode());
        assertEquals("gone", error.getResponseBody());
        assertEquals("no-such-file", error.header("x-reason").orElseThrow());
        assertNotNull(error.getHeaders().get("X-Reason"));
        assertEquals(4, error.getResponseBytes().length);
        assertFalse(error.isBodyTruncated());
    }

    @Test
    void defaultModeStillThrowsForApiResponse() throws Exception {
        TestServer server = notFoundServer();
        ErrorModeApi api = client(ErrorModeApi.class, config(server).maxRetries(0).build());

        assertThrows(NotFoundException.class, api::missingResponse);
    }

    @Test
    void optOutReturnsErrorStatusForStringApiResponse() throws Exception {
        TestServer server = notFoundServer();
        ErrorModeApi api = client(ErrorModeApi.class, config(server).maxRetries(0).throwOnError(false).build());

        ApiResponse<String> response = api.missingResponse();

        assertEquals(404, response.status());
        assertFalse(response.isSuccessful());
        assertEquals("gone", response.body());
        assertEquals("no-such-file", response.header("X-Reason").orElseThrow());
    }

    @Test
    void optOutReturnsErrorStatusForByteArrayApiResponse() throws Exception {
        TestServer server = notFoundServer();
        ErrorModeApi api = client(ErrorModeApi.class, config(server).maxRetries(0).throwOnError(false).build());

        ApiResponse<byte[]> response = api.missingBytes();

        assertEquals(404, response.status());
        assertEquals("gone", new String(response.body(), StandardCharsets.UTF_8));
    }

    @Test
    void optOutReturnsErrorStatusForStreamResponse() throws Exception {
        TestServer server = notFoundServer();
        ErrorModeApi api = client(ErrorModeApi.class, config(server).maxRetries(0).throwOnError(false).build());

        try (StreamResponse stream = api.missingStream()) {
            assertEquals(404, stream.status());
            assertEquals("no-such-file", stream.header("X-Reason").orElseThrow());
            assertEquals("gone", new String(stream.inputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void optOutStillThrowsForDtoApiResponse() throws Exception {
        TestServer server = notFoundServer();
        ErrorModeApi api = client(ErrorModeApi.class, config(server).maxRetries(0).throwOnError(false).build());

        assertThrows(NotFoundException.class, api::missingItem);
    }

    @Test
    void optOutStillThrowsForPlainStringReturn() throws Exception {
        TestServer server = notFoundServer();
        ErrorModeApi api = client(ErrorModeApi.class, config(server).maxRetries(0).throwOnError(false).build());

        assertThrows(NotFoundException.class, api::missingText);
    }
}