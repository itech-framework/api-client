package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.PUT;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.exceptions.ServiceUnavailableException;
import io.github.itech_framework.api_client.exceptions.UnauthorizedException;

public class RetryGuardTest extends BaseClientTest {

    public interface UploadApi {
        @PUT("/blob")
        String putStream(@Body InputStream data);

        @PUT("/blob")
        String putBytes(@Body byte[] data);

        @PUT("/secure")
        String putSecureBytes(@Body byte[] data);

        @PUT("/secure")
        String putSecureStream(@Body InputStream data);
    }

    private static final class CountingAuth implements AuthenticationProvider {
        final AtomicInteger refreshes = new AtomicInteger();
        volatile String token = "old";

        @Override
        public void apply(HttpRequest request, HttpContext context) {
            request.setHeader("Authorization", "Bearer " + token);
        }

        @Override
        public void refresh() {
            refreshes.incrementAndGet();
            token = "new";
        }

        @Override
        public boolean isExpired() {
            return false;
        }

        @Override
        public boolean retryOnUnauthorized() {
            return true;
        }
    }

    @Test
    void aStreamBodyIsSentOnceEvenForPut() throws Exception {
        TestServer server = server();
        server.route("/blob", (request, call) -> call < 2 ? Response.empty(503) : Response.of(200, "ok"));
        UploadApi api = client(UploadApi.class, config(server).build());

        assertThrows(ServiceUnavailableException.class,
                () -> api.putStream(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))));

        assertEquals(1, server.requestsTo("/blob").size());
    }

    @Test
    void aByteArrayBodyIsStillRetriedForPut() throws Exception {
        TestServer server = server();
        server.route("/blob", (request, call) -> call < 2 ? Response.empty(503) : Response.of(200, "ok"));
        UploadApi api = client(UploadApi.class, config(server).build());

        assertEquals("ok", api.putBytes("payload".getBytes(StandardCharsets.UTF_8)));

        assertEquals(2, server.requestsTo("/blob").size());
    }

    @Test
    void unauthorizedRetryHappensWhenTheBodyCanBeResent() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> "Bearer new".equals(request.header("Authorization"))
                ? Response.of(200, "ok")
                : Response.empty(401));
        CountingAuth auth = new CountingAuth();
        UploadApi api = client(UploadApi.class, config(server).authenticationProvider(auth).build());

        assertEquals("ok", api.putSecureBytes("payload".getBytes(StandardCharsets.UTF_8)));

        assertEquals(1, auth.refreshes.get());
        assertEquals(2, server.requestsTo("/secure").size());
    }

    @Test
    void unauthorizedRetryIsSkippedForAStreamBody() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> "Bearer new".equals(request.header("Authorization"))
                ? Response.of(200, "ok")
                : Response.empty(401));
        CountingAuth auth = new CountingAuth();
        UploadApi api = client(UploadApi.class, config(server).authenticationProvider(auth).build());

        assertThrows(UnauthorizedException.class,
                () -> api.putSecureStream(new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8))));

        assertEquals(0, auth.refreshes.get());
        assertEquals(1, server.requestsTo("/secure").size());
    }
}