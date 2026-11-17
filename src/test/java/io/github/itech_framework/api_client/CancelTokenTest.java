package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.RequestCancelledException;
import io.github.itech_framework.api_client.utils.CancelToken;

public class CancelTokenTest extends BaseClientTest {

    public interface SlowApi {
        @GET("/slow")
        String slow(CancelToken token);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void cancelStopsARequestThatIsWaitingForTheServer() throws Exception {
        TestServer server = server();
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.route("/slow", (request, call) -> {
            arrived.countDown();
            release.await(10, TimeUnit.SECONDS);
            return Response.of(200, "late");
        });
        SlowApi api = client(SlowApi.class, config(server).build());
        CancelToken token = new CancelToken();

        CompletableFuture<String> call = CompletableFuture.supplyAsync(() -> api.slow(token));
        try {
            assertTrue(arrived.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            token.cancel();

            ExecutionException error = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));

            assertTrue(hasCause(error, RequestCancelledException.class));
            assertTrue(token.isCancelled());
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3000);
        } finally {
            release.countDown();
        }
    }

    @Test
    void aTokenCancelledBeforeTheCallStopsItAtOnce() throws Exception {
        TestServer server = server();
        AtomicInteger hits = new AtomicInteger();
        server.route("/slow", (request, call) -> {
            hits.incrementAndGet();
            return Response.of(200, "ok");
        });
        SlowApi api = client(SlowApi.class, config(server).build());
        CancelToken token = new CancelToken();
        token.cancel();

        RuntimeException error = assertThrows(RuntimeException.class, () -> api.slow(token));

        assertTrue(hasCause(error, RequestCancelledException.class));
        assertEquals(0, hits.get());
    }

    @Test
    void aTokenThatIsNotCancelledDoesNothing() throws Exception {
        TestServer server = server();
        server.route("/slow", (request, call) -> Response.of(200, "ok"));
        SlowApi api = client(SlowApi.class, config(server).build());
        CancelToken token = new CancelToken();

        assertEquals("ok", api.slow(token));
        assertFalse(token.isCancelled());

        token.cancel();
        assertTrue(token.isCancelled());
    }
}