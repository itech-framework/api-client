package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.ApiKey;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BasicAuth;
import io.github.itech_framework.api_client.annotations.authentications.BearerToken;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.parameters.Auth;
import io.github.itech_framework.api_client.annotations.parameters.Query;
import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.utils.enums.AuthType;
import io.github.itech_framework.core.exceptions.FrameworkException;

public class AuthTest extends BaseClientTest {

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
    }

    @Authenticated(AuthType.API_KEY)
    @ApiKey(name = "X-Api-Key", value = "key-header")
    public interface ApiKeyHeaderApi {
        @GET("/secure")
        String secure();
    }

    @Authenticated(AuthType.API_KEY)
    @ApiKey(name = "api_key", value = "key-query", inHeader = false)
    public interface ApiKeyQueryApi {
        @GET("/secure")
        String secure(@Query("a") String a);
    }

    @Authenticated(AuthType.BEARER)
    @BearerToken(token = "class-token")
    public interface OverrideApi {
        @GET("/a")
        String classLevel();

        @GET("/b")
        @Authenticated(AuthType.BASIC)
        @BasicAuth(username = "m", password = "n")
        String methodLevel();
    }

    @Authenticated(AuthType.BASIC)
    public interface BrokenAuthApi {
        @GET("/x")
        String x();
    }

    public interface DynamicAuthApi {
        @GET("/echo-auth")
        String call(@Auth AuthenticationProvider auth);

        @GET("/echo-auth")
        String anonymous();
    }

    public static final class HeaderAuth implements AuthenticationProvider {
        private final String token;

        public HeaderAuth(String token) {
            this.token = token;
        }

        @Override
        public void apply(HttpRequest request, HttpContext context) {
            request.setHeader("X-Token", token);
        }

        @Override
        public void refresh() {
        }

        @Override
        public boolean isExpired() {
            return false;
        }
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static Response echoToken(TestServer.Received request, int call) {
        String token = request.header("X-Token");
        return Response.of(200, token == null ? "none" : token);
    }

    @Test
    void basicAuthAnnotationAddsAuthorizationHeader() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> Response.of(200, "ok"));

        client(BasicApi.class, config(server).build()).secure();

        assertEquals(basic("user", "pass"), server.requests().get(0).header("Authorization"));
    }

    @Test
    void bearerAnnotationAddsAuthorizationHeader() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> Response.of(200, "ok"));

        client(BearerApi.class, config(server).build()).secure();

        assertEquals("Bearer abc123", server.requests().get(0).header("Authorization"));
    }

    @Test
    void apiKeyAnnotationCanUseHeader() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> Response.of(200, "ok"));

        client(ApiKeyHeaderApi.class, config(server).build()).secure();

        assertEquals("key-header", server.requests().get(0).header("X-Api-Key"));
    }

    @Test
    void apiKeyAnnotationCanUseQueryParameter() throws Exception {
        TestServer server = server();
        server.route("/secure", (request, call) -> Response.of(200, "ok"));

        client(ApiKeyQueryApi.class, config(server).build()).secure("1");

        String query = server.requests().get(0).rawQuery();
        assertTrue(query.contains("a=1"), query);
        assertTrue(query.contains("api_key=key-query"), query);
    }

    @Test
    void methodLevelAuthOverridesClassLevelAuth() throws Exception {
        TestServer server = server();
        server.route("/a", (request, call) -> Response.of(200, "ok"));
        server.route("/b", (request, call) -> Response.of(200, "ok"));
        OverrideApi api = client(OverrideApi.class, config(server).build());

        api.classLevel();
        api.methodLevel();

        assertEquals("Bearer class-token", server.requestsTo("/a").get(0).header("Authorization"));
        assertEquals(basic("m", "n"), server.requestsTo("/b").get(0).header("Authorization"));
    }

    @Test
    void authenticatedWithoutMatchingAnnotationFailsAtStartup() throws Exception {
        TestServer server = server();

        FrameworkException error = assertThrows(FrameworkException.class,
                () -> client(BrokenAuthApi.class, config(server).build()));

        assertTrue(error.getMessage().contains("BasicAuth"));
    }

    @Test
    void globalConfigProviderIsUsedWhenNothingElseApplies() throws Exception {
        TestServer server = server();
        server.route("/echo-auth", AuthTest::echoToken);
        DynamicAuthApi api = client(DynamicAuthApi.class,
                config(server).authenticationProvider(new HeaderAuth("global")).build());

        assertEquals("global", api.anonymous());
        assertEquals("explicit", api.call(new HeaderAuth("explicit")));
    }

    @Test
    void concurrentCallsKeepTheirOwnAuthProvider() throws Exception {
        TestServer server = server();
        server.route("/echo-auth", AuthTest::echoToken);
        DynamicAuthApi api = client(DynamicAuthApi.class, config(server).maxConnections(32).maxRetries(0).build());

        int threads = 16;
        int iterations = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            String token = "token-" + t;
            futures.add(pool.submit(() -> {
                List<String> mismatches = new ArrayList<>();
                HeaderAuth auth = new HeaderAuth(token);
                for (int i = 0; i < iterations; i++) {
                    String seen = api.call(auth);
                    if (!token.equals(seen)) {
                        mismatches.add(token + " saw " + seen);
                    }
                }
                return mismatches;
            }));
        }

        List<String> mismatches = new ArrayList<>();
        for (Future<List<String>> future : futures) {
            mismatches.addAll(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertTrue(mismatches.isEmpty(), mismatches.toString());
        assertEquals("none", api.anonymous());
    }
}