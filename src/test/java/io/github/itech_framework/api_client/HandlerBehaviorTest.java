package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.parameters.Path;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.exceptions.NotFoundException;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.core.exceptions.FrameworkException;

public class HandlerBehaviorTest extends BaseClientTest {

    public interface AsyncApi {
        @GET("/ok")
        CompletableFuture<String> ok();

        @GET("/missing")
        CompletableFuture<String> missing();

        @GET("/user")
        CompletableFuture<RequestBuildingTest.User> user();
    }

    public interface PlainApi {
        @GET("/ping")
        String ping();

        @GET("/ping")
        String pingChecked() throws IOException;

        @GET("/missing")
        String missing();
    }

    public interface BadPlaceholderApi {
        @GET("/x/{id}")
        String get();
    }

    public interface UnannotatedApi {
        String nothing();
    }

    public interface NullPathApi {
        @GET("/x/{id}")
        String get(@Path("id") String id);
    }

    public static final class RecordingInterceptor implements Interceptor {
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger responses = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        @Override
        public void onRequest(HttpRequest request, HttpContext context) {
            requests.incrementAndGet();
            threads.add(Thread.currentThread());
        }

        @Override
        public void onResponse(HttpResponse response, HttpContext context) {
            responses.incrementAndGet();
        }

        @Override
        public void onError(Exception exception, HttpContext context) {
            errors.incrementAndGet();
        }
    }

    @Test
    void asyncCallsRunOnDaemonPoolThreads() throws Exception {
        TestServer server = server();
        server.route("/ok", (request, call) -> Response.of(200, "ok"));
        RecordingInterceptor recorder = new RecordingInterceptor();
        AsyncApi api = client(AsyncApi.class, config(server).addInterceptor(recorder).build());

        assertEquals("ok", api.ok().get(5, TimeUnit.SECONDS));

        Thread worker = recorder.threads.get(0);
        assertTrue(worker.isDaemon());
        assertTrue(worker.getName().startsWith("api-client-AsyncApi"), worker.getName());
    }

    @Test
    void asyncParsesJsonResult() throws Exception {
        TestServer server = server();
        server.route("/user", (request, call) -> Response.json(200, "{\"id\":3,\"name\":\"Mya\"}"));
        AsyncApi api = client(AsyncApi.class, config(server).build());

        RequestBuildingTest.User user = api.user().get(5, TimeUnit.SECONDS);

        assertEquals(3, user.id);
        assertEquals("Mya", user.name);
    }

    @Test
    void asyncFailureCompletesFutureExceptionally() throws Exception {
        TestServer server = server();
        server.route("/missing", (request, call) -> Response.empty(404));
        AsyncApi api = client(AsyncApi.class, config(server).maxRetries(0).build());

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> api.missing().get(5, TimeUnit.SECONDS));

        assertInstanceOf(NotFoundException.class, error.getCause());
    }

    @Test
    void disabledAsyncStillReturnsACompletedFuture() throws Exception {
        TestServer server = server();
        server.route("/ok", (request, call) -> Response.of(200, "ok"));
        server.route("/missing", (request, call) -> Response.empty(404));
        AsyncApi api = client(AsyncApi.class, config(server).enableAsync(false).maxRetries(0).build());

        CompletableFuture<String> success = api.ok();
        CompletableFuture<String> failure = api.missing();

        assertTrue(success.isDone());
        assertEquals("ok", success.get());
        assertTrue(failure.isCompletedExceptionally());
    }

    @Test
    void interceptorsSeeRequestResponseAndError() throws Exception {
        TestServer server = server();
        server.route("/missing", (request, call) -> Response.empty(404));
        RecordingInterceptor recorder = new RecordingInterceptor();
        PlainApi api = client(PlainApi.class, config(server).maxRetries(0).addInterceptor(recorder).build());

        assertThrows(NotFoundException.class, api::missing);

        assertEquals(1, recorder.requests.get());
        assertEquals(1, recorder.responses.get());
        assertEquals(1, recorder.errors.get());
    }

    @Test
    void ioFailureBecomesApiExceptionWhenNotDeclared() throws Exception {
        PlainApi api = client(PlainApi.class,
                ApiClientConfig.builder().baseUrl("http://127.0.0.1:1").maxRetries(0).build());

        ApiException error = assertThrows(ApiException.class, api::ping);

        assertEquals("IO_ERROR", error.getErrorCode());
        assertInstanceOf(IOException.class, error.getCause());
    }

    @Test
    void ioFailureIsRethrownWhenDeclared() throws Exception {
        PlainApi api = client(PlainApi.class,
                ApiClientConfig.builder().baseUrl("http://127.0.0.1:1").maxRetries(0).build());

        assertThrows(IOException.class, api::pingChecked);
    }

    @Test
    void placeholderWithoutPathParameterFailsAtStartup() throws Exception {
        TestServer server = server();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client(BadPlaceholderApi.class, config(server).build()));

        assertTrue(error.getMessage().contains("{id}"), error.getMessage());
    }

    @Test
    void methodWithoutHttpAnnotationFailsAtStartup() throws Exception {
        TestServer server = server();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> client(UnannotatedApi.class, config(server).build()));

        assertTrue(error.getMessage().contains("No HTTP method annotation"), error.getMessage());
    }

    @Test
    void nullPathParameterIsRejected() throws Exception {
        TestServer server = server();
        NullPathApi api = client(NullPathApi.class, config(server).build());

        assertThrows(IllegalArgumentException.class, () -> api.get(null));
        assertEquals(0, server.requests().size());
    }

    @Test
    void missingBaseUrlGivesAHelpfulError() throws Exception {
        PlainApi api = client(PlainApi.class, ApiClientConfig.builder().build());

        FrameworkException error = assertThrows(FrameworkException.class, api::ping);

        assertTrue(error.getMessage().contains("Base url is not defined"), error.getMessage());
    }
}