package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BearerToken;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.interceptor.HeaderRedactor;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.utils.enums.AuthType;

public class HeaderRedactorTest extends BaseClientTest {

    public static final class LoggingInterceptor implements Interceptor {
        final List<Map<String, List<String>>> logged = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeSend(HttpRequest request, HttpContext context) {
            logged.add(HeaderRedactor.defaults().headers(request));
        }
    }

    @Authenticated(AuthType.BEARER)
    @BearerToken(token = "abc123")
    public interface BearerApi {
        @GET("/secure")
        String secure();
    }

    @Test
    void sensitiveNamesAreMatchedIgnoringCase() {
        HeaderRedactor redactor = HeaderRedactor.defaults();

        assertTrue(redactor.isSensitive("AUTHORIZATION"));
        assertTrue(redactor.isSensitive("Proxy-Authorization"));
        assertTrue(redactor.isSensitive("cookie"));
        assertTrue(redactor.isSensitive("Set-Cookie"));
        assertTrue(redactor.isSensitive("x-API-key"));
        assertFalse(redactor.isSensitive("Content-Type"));
        assertFalse(redactor.isSensitive(null));
    }

    @Test
    void authorizationKeepsOnlyTheScheme() {
        HeaderRedactor redactor = HeaderRedactor.defaults();

        assertEquals("Bearer ***", redactor.value("Authorization", "Bearer abc123"));
        assertEquals("Basic ***", redactor.value("authorization", "Basic dXNlcjpwYXNz"));
        assertEquals("***", redactor.value("Authorization", "token-without-scheme"));
    }

    @Test
    void otherSensitiveHeadersAreFullyMasked() {
        HeaderRedactor redactor = HeaderRedactor.defaults();

        assertEquals("***", redactor.value("Cookie", "session=secret"));
        assertEquals("***", redactor.value("X-Api-Key", "key-1"));
    }

    @Test
    void ordinaryHeadersAndNullValuesAreUnchanged() {
        HeaderRedactor redactor = HeaderRedactor.defaults();

        assertEquals("application/json", redactor.value("Content-Type", "application/json"));
        assertNull(redactor.value("Authorization", null));
    }

    @Test
    void extraNamesCanBeAddedWithoutChangingTheDefaults() {
        HeaderRedactor custom = HeaderRedactor.defaults().with("X-Custom-Key", " X-Other ");

        assertTrue(custom.isSensitive("x-custom-key"));
        assertTrue(custom.isSensitive("X-OTHER"));
        assertTrue(custom.isSensitive("Authorization"));
        assertFalse(HeaderRedactor.defaults().isSensitive("X-Custom-Key"));
    }

    @Test
    void headersOfARequestAreRedactedAndKeepRepeatedValues() {
        HttpGet request = new HttpGet("http://localhost/x");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("X-Multi", "a");
        request.addHeader("X-Multi", "b");

        Map<String, List<String>> headers = HeaderRedactor.defaults().headers(request);

        assertEquals(List.of("Bearer ***"), headers.get("authorization"));
        assertEquals(List.of("a", "b"), headers.get("X-Multi"));
    }

    @Test
    void aHeaderMapIsRedacted() {
        Map<String, List<String>> redacted = HeaderRedactor.defaults()
                .redact(Map.of("Set-Cookie", List.of("a=1", "b=2"), "Server", List.of("test")));

        assertEquals(List.of("***", "***"), redacted.get("set-cookie"));
        assertEquals(List.of("test"), redacted.get("Server"));
    }

    @Test
    void anInterceptorCanLogRequestHeadersWithoutTheSecret() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> Response.of(200, "ok"));
        LoggingInterceptor interceptor = new LoggingInterceptor();

        client(BearerApi.class, config(server).addInterceptor(interceptor).build()).secure();

        assertEquals(1, interceptor.logged.size());
        assertEquals(List.of("Bearer ***"), interceptor.logged.get(0).get("Authorization"));
        assertFalse(interceptor.logged.get(0).toString().contains("abc123"));
        assertEquals("Bearer abc123", server.requests().get(0).header("Authorization"));
    }
}