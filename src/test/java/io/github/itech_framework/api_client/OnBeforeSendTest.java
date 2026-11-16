package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.ApiKey;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BasicAuth;
import io.github.itech_framework.api_client.annotations.authentications.BearerToken;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.utils.enums.AuthType;

public class OnBeforeSendTest extends BaseClientTest {

    public static final class Recorder implements Interceptor {
        final List<String> authAtRequest = new CopyOnWriteArrayList<>();
        final List<String> authBeforeSend = new CopyOnWriteArrayList<>();
        final List<String> apiKeyBeforeSend = new CopyOnWriteArrayList<>();
        final List<Object> attempts = new CopyOnWriteArrayList<>();

        @Override
        public void onRequest(HttpRequest request, HttpContext context) {
            authAtRequest.add(headerValue(request, "Authorization"));
        }

        @Override
        public void onBeforeSend(HttpRequest request, HttpContext context) {
            authBeforeSend.add(headerValue(request, "Authorization"));
            apiKeyBeforeSend.add(headerValue(request, "X-Api-Key"));
            attempts.add(context.getAttribute(Interceptor.ATTEMPT));
        }

        private static String headerValue(HttpRequest request, String name) {
            Header header = request.getFirstHeader(name);
            return header == null ? null : header.getValue();
        }
    }

    public static final class ThrowingInterceptor implements Interceptor {
        @Override
        public void onBeforeSend(HttpRequest request, HttpContext context) {
            throw new IllegalStateException("boom");
        }
    }

    @Authenticated(AuthType.BASIC)
    @BasicAuth(username = "user", password = "pass")
    public interface BasicApi {
        @GET("/secure")
        String secure();
    }

    @Authenticated(AuthType.BEARER)
    @BearerToken(token = "abc123")
    public interface BearerApi {
        @GET("/secure")
        String secure();

        @GET("/flaky")
        String flaky();
    }

    @Authenticated(AuthType.API_KEY)
    @ApiKey(name = "X-Api-Key", value = "key-header")
    public interface ApiKeyApi {
        @GET("/secure")
        String secure();
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private TestServer okServer() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    @Test
    void beforeSendSeesBasicAuthorization() throws Exception {
        TestServer server = okServer();
        Recorder recorder = new Recorder();

        client(BasicApi.class, config(server).addInterceptor(recorder).build()).secure();

        assertEquals(List.of(basic("user", "pass")), recorder.authBeforeSend);
    }

    @Test
    void beforeSendSeesBearerAuthorization() throws Exception {
        TestServer server = okServer();
        Recorder recorder = new Recorder();

        client(BearerApi.class, config(server).addInterceptor(recorder).build()).secure();

        assertEquals(List.of("Bearer abc123"), recorder.authBeforeSend);
    }

    @Test
    void beforeSendSeesApiKeyHeader() throws Exception {
        TestServer server = okServer();
        Recorder recorder = new Recorder();

        client(ApiKeyApi.class, config(server).addInterceptor(recorder).build()).secure();

        assertEquals(List.of("key-header"), recorder.apiKeyBeforeSend);
    }

    @Test
    void onRequestStillRunsBeforeAuthIsApplied() throws Exception {
        TestServer server = okServer();
        Recorder recorder = new Recorder();

        client(BearerApi.class, config(server).addInterceptor(recorder).build()).secure();

        assertEquals(1, recorder.authAtRequest.size());
        assertNull(recorder.authAtRequest.get(0));
        assertEquals("Bearer abc123", server.requests().get(0).header("Authorization"));
    }

    @Test
    void attemptNumberRisesOnRetryAndAuthIsSeenOnEveryAttempt() throws Exception {
        TestServer server = server();
        server.route("/flaky", (request, call) -> call == 1 ? Response.empty(503) : Response.of(200, "ok"));
        Recorder recorder = new Recorder();

        assertEquals("ok", client(BearerApi.class, config(server).addInterceptor(recorder).build()).flaky());

        assertEquals(2, server.requestsTo("/flaky").size());
        assertEquals(List.of(1, 2), recorder.attempts);
        assertEquals(List.of("Bearer abc123", "Bearer abc123"), recorder.authBeforeSend);
    }

    @Test
    void failingBeforeSendDoesNotBreakTheCall() throws Exception {
        TestServer server = okServer();

        String result = client(BearerApi.class,
                config(server).addInterceptor(new ThrowingInterceptor()).build()).secure();

        assertEquals("ok", result);
        assertEquals(1, server.requestsTo("/secure").size());
    }
}