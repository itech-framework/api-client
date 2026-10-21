package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.auth.TokenManager;
import io.github.itech_framework.api_client.auth.TokenManager.Credentials;

public class TokenManagerTest extends BaseClientTest {

    private static Response token(String accessToken, String refreshToken, int expiresIn) {
        String refresh = refreshToken == null ? "" : ",\"refresh_token\":\"" + refreshToken + "\"";
        return Response.json(200,
                "{\"access_token\":\"" + accessToken + "\",\"expires_in\":" + expiresIn + refresh + "}");
    }

    @Test
    void slowTokenEndpointDoesNotBlockOtherClients() throws Exception {
        TestServer server = server();
        server.route("/slow", (request, call) -> {
            Thread.sleep(1500);
            return token("slow-token", null, 3600);
        });
        server.route("/fast", (request, call) -> token("fast-token", null, 3600));
        Credentials slow = new Credentials("c-slow", "s", server.url() + "/slow");
        Credentials fast = new Credentials("c-fast", "s", server.url() + "/fast");

        CompletableFuture<String> slowFetch = CompletableFuture.supplyAsync(() -> TokenManager.getToken(slow));
        Thread.sleep(200);
        long start = System.nanoTime();
        String fastToken = TokenManager.getToken(fast);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals("fast-token", fastToken);
        assertTrue(elapsedMillis < 700, "fast client waited " + elapsedMillis + " ms");
        assertEquals("slow-token", slowFetch.get(10, TimeUnit.SECONDS));
    }

    @Test
    void concurrentCallsForTheSameClientFetchOneToken() throws Exception {
        TestServer server = server();
        server.route("/once", (request, call) -> {
            Thread.sleep(300);
            return token("t-" + call, null, 3600);
        });
        Credentials credentials = new Credentials("c-once", "s", server.url() + "/once");

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> TokenManager.getToken(credentials)));
        }
        Set<String> seen = new HashSet<>();
        for (Future<String> future : futures) {
            seen.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertEquals(Set.of("t-1"), seen);
        assertEquals(1, server.requestsTo("/once").size());
    }

    @Test
    void fallsBackToClientCredentialsWhenRefreshFails() throws Exception {
        TestServer server = server();
        server.route("/refresh", (request, call) -> {
            if (call == 1) {
                return token("t-1", "r1", 1);
            }
            if (request.body().contains("grant_type=refresh_token")) {
                return Response.empty(400);
            }
            return token("t-fresh", null, 3600);
        });
        Credentials credentials = new Credentials("c-refresh", "s", server.url() + "/refresh");

        assertEquals("t-1", TokenManager.getToken(credentials));
        Thread.sleep(1300);
        assertEquals("t-fresh", TokenManager.getToken(credentials));

        List<TestServer.Received> calls = server.requestsTo("/refresh");
        assertEquals(3, calls.size());
        assertTrue(calls.get(0).body().contains("grant_type=client_credentials"));
        assertTrue(calls.get(1).body().contains("grant_type=refresh_token"));
        assertTrue(calls.get(2).body().contains("grant_type=client_credentials"));
    }

    @Test
    void invalidateForcesANewTokenRequest() throws Exception {
        TestServer server = server();
        server.route("/inv", (request, call) -> token("t-" + call, null, 3600));
        Credentials credentials = new Credentials("c-inv", "s", server.url() + "/inv");

        assertEquals("t-1", TokenManager.getToken(credentials));
        assertEquals("t-1", TokenManager.getToken(credentials));
        TokenManager.invalidate(credentials);
        assertEquals("t-2", TokenManager.getToken(credentials));
    }
}