package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BearerToken;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;
import io.github.itech_framework.api_client.transport.impl.DefaultHttpTransportProvider;
import io.github.itech_framework.api_client.utils.enums.AuthType;

public class CustomTransportTest extends BaseClientTest {

    public static final class CountingTransport implements HttpTransportProvider {
        private final DefaultHttpTransportProvider delegate;
        final AtomicInteger twoArgCalls = new AtomicInteger();

        CountingTransport(ApiClientConfig delegateConfig) {
            this.delegate = new DefaultHttpTransportProvider(delegateConfig);
        }

        @Override
        public CloseableHttpClient createHttpClient() {
            return delegate.createHttpClient();
        }

        @Override
        public CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider customAuth)
                throws IOException {
            twoArgCalls.incrementAndGet();
            return delegate.execute(request, customAuth);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    public static final class CountingInterceptor implements Interceptor {
        final AtomicInteger onRequest = new AtomicInteger();
        final AtomicInteger onBeforeSend = new AtomicInteger();
        final AtomicInteger onResponse = new AtomicInteger();

        @Override
        public void onRequest(HttpRequest request, HttpContext context) {
            onRequest.incrementAndGet();
        }

        @Override
        public void onBeforeSend(HttpRequest request, HttpContext context) {
            onBeforeSend.incrementAndGet();
        }

        @Override
        public void onResponse(HttpResponse response, HttpContext context) {
            onResponse.incrementAndGet();
        }
    }

    @Authenticated(AuthType.BEARER)
    @BearerToken(token = "abc123")
    public interface BearerApi {
        @GET("/secure")
        String secure();
    }

    private TestServer okServer() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    private CountingTransport transportFor(TestServer server) {
        return new CountingTransport(ApiClientConfig.builder().baseUrl(server.url()).build());
    }

    @Test
    void twoArgumentOnlyTransportStillExecutesRequests() throws Exception {
        TestServer server = okServer();
        CountingTransport transport = transportFor(server);

        String result = client(BearerApi.class, config(server).httpTransportProvider(transport).build()).secure();

        assertEquals("ok", result);
        assertEquals(1, transport.twoArgCalls.get());
        assertEquals("Bearer abc123", server.requests().get(0).header("Authorization"));
    }

    @Test
    void requestAndResponseHooksStillFireWithTwoArgumentOnlyTransport() throws Exception {
        TestServer server = okServer();
        CountingTransport transport = transportFor(server);
        CountingInterceptor interceptor = new CountingInterceptor();

        client(BearerApi.class,
                config(server).httpTransportProvider(transport).addInterceptor(interceptor).build()).secure();

        assertEquals(1, interceptor.onRequest.get());
        assertEquals(1, interceptor.onResponse.get());
        assertEquals(0, interceptor.onBeforeSend.get());
    }
}