package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.InternalServerErrorException;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.core.exceptions.FrameworkException;

public class InterceptorTest extends DynamicApiTest {

    public static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    public static class FirstInterceptor implements Interceptor {
        @Override
        public void onRequest(HttpRequest request, HttpContext context) {
            EVENTS.add("first:request");
        }

        @Override
        public void onResponse(HttpResponse response, HttpContext context) {
            EVENTS.add("first:response:" + response.getStatusLine().getStatusCode());
        }

        @Override
        public void onError(Exception exception, HttpContext context) {
            EVENTS.add("first:error");
        }
    }

    public static class SecondInterceptor implements Interceptor {
        @Override
        public void onRequest(HttpRequest request, HttpContext context) {
            Method method = (Method) context.getAttribute(Interceptor.METHOD);
            EVENTS.add("second:request:" + method.getName());
        }
    }

    public static class NeedsArgInterceptor implements Interceptor {
        public NeedsArgInterceptor(String ignored) {
        }
    }

    @ApiClient(interceptors = { FirstInterceptor.class, SecondInterceptor.class })
    public interface HookedApi {
        @GET("/ok")
        String ok();

        @GET("/boom")
        String boom();

        @GET("/flaky")
        String flaky();
    }

    @ApiClient(interceptors = NeedsArgInterceptor.class)
    public interface BadHookApi {
        @GET("/ok")
        String ok();
    }

    @BeforeEach
    void clearEvents() {
        EVENTS.clear();
    }

    @Test
    void interceptorsFromTheInterfaceRunInArrayOrder() throws Exception {
        TestServer server = server();
        server.route("/ok", (request, call) -> Response.of(200, "ok"));
        useProperties("api.client.hookedapi.base.url", server.url());
        HookedApi api = processed(HookedApi.class);

        assertEquals("ok", api.ok());

        assertEquals(List.of("first:request", "second:request:ok", "first:response:200"), EVENTS);
    }

    @Test
    void anErrorStatusCallsOnResponseThenOnError() throws Exception {
        TestServer server = server();
        server.route("/boom", (request, call) -> Response.empty(500));
        useProperties("api.client.hookedapi.base.url", server.url());
        HookedApi api = processed(HookedApi.class);

        assertThrows(InternalServerErrorException.class, api::boom);

        assertEquals(List.of("first:request", "second:request:boom", "first:response:500", "first:error"), EVENTS);
    }

    @Test
    void interceptorsRunOncePerCallNotOncePerRetry() throws Exception {
        TestServer server = server();
        server.route("/flaky", (request, call) -> call < 2 ? Response.empty(503) : Response.of(200, "ok"));
        useProperties("api.client.hookedapi.base.url", server.url(), "api.client.hookedapi.retry.backoff", "1",
                "api.client.hookedapi.retry.max.backoff", "5");
        HookedApi api = processed(HookedApi.class);

        assertEquals("ok", api.flaky());

        assertEquals(2, server.requestsTo("/flaky").size());
        assertEquals(List.of("first:request", "second:request:flaky", "first:response:200"), EVENTS);
    }

    @Test
    void anInterceptorWithoutANoArgConstructorFailsAtStartup() {
        useProperties("api.client.badhookapi.base.url", "http://localhost:1");

        FrameworkException failure = assertThrows(FrameworkException.class, () -> processed(BadHookApi.class));

        assertTrue(failure.getMessage().contains("NeedsArgInterceptor"), failure.getMessage());
        assertTrue(failure.getMessage().contains("no-argument constructor"), failure.getMessage());
    }
}