package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.handlers.DynamicApiClientInvocationHandler;

public class AsyncQueuePolicyTest extends BaseClientTest {

    private static final int QUEUE_CAPACITY = 100;

    public interface SlowApi {
        @GET("/slow")
        CompletableFuture<String> slow();
    }

    private ApiClientConfig.Builder singleWorker(TestServer server, ApiClientConfig.QueueFullPolicy policy) {
        return config(server).maxRetries(0).asyncThreadPoolSize(1).asyncQueueFullPolicy(policy);
    }

    private static List<CompletableFuture<String>> fillWorkerAndQueue(SlowApi api) {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < QUEUE_CAPACITY + 1; i++) {
            futures.add(api.slow());
        }
        return futures;
    }

    @Test
    void defaultPolicyIsReject() {
        assertEquals(ApiClientConfig.QueueFullPolicy.REJECT,
                ApiClientConfig.builder().build().getAsyncQueueFullPolicy());
    }

    @Test
    void rejectPolicyFailsTheFutureWhenTheQueueIsFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TestServer server = server();
        server.route("/slow", (request, call) -> {
            release.await(10, TimeUnit.SECONDS);
            return Response.of(200, "ok");
        });
        SlowApi api = client(SlowApi.class, singleWorker(server, ApiClientConfig.QueueFullPolicy.REJECT).build());
        List<CompletableFuture<String>> accepted = fillWorkerAndQueue(api);

        CompletableFuture<String> rejected = api.slow();
        release.countDown();

        assertTrue(rejected.isCompletedExceptionally());
        ExecutionException error = assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS));
        ApiException cause = assertInstanceOf(ApiException.class, error.getCause());
        assertEquals("QUEUE_FULL", cause.getErrorCode());
        for (CompletableFuture<String> future : accepted) {
            assertEquals("ok", future.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerRunsPolicyRunsTheOverflowCallOnTheCallingThread() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TestServer server = server();
        server.route("/slow", (request, call) -> {
            if (call == 1) {
                release.await(5, TimeUnit.SECONDS);
            }
            return Response.of(200, "ok");
        });
        HandlerBehaviorTest.RecordingInterceptor recorder = new HandlerBehaviorTest.RecordingInterceptor();
        SlowApi api = client(SlowApi.class,
                singleWorker(server, ApiClientConfig.QueueFullPolicy.CALLER_RUNS).addInterceptor(recorder).build());
        List<CompletableFuture<String>> accepted = fillWorkerAndQueue(api);

        CompletableFuture<String> overflow = api.slow();
        release.countDown();

        assertTrue(overflow.isDone());
        assertEquals("ok", overflow.get(1, TimeUnit.SECONDS));
        assertTrue(recorder.threads.contains(Thread.currentThread()));
        for (CompletableFuture<String> future : accepted) {
            assertEquals("ok", future.get(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(ApiClientConfig.QueueFullPolicy.class)
    void callsAfterCloseFailInsteadOfHanging(ApiClientConfig.QueueFullPolicy policy) throws Exception {
        TestServer server = server();
        SlowApi api = client(SlowApi.class, singleWorker(server, policy).build());
        ((DynamicApiClientInvocationHandler) Proxy.getInvocationHandler(api)).close();

        CompletableFuture<String> future = api.slow();

        assertTrue(future.isCompletedExceptionally());
        ExecutionException error = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }
}