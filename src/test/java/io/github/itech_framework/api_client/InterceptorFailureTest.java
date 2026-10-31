package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.interceptor.Interceptor;

public class InterceptorFailureTest extends BaseClientTest {

    public interface PlainApi {
        @GET("/data")
        String data();
    }

    @Test
    void aFailingOnErrorInterceptorDoesNotHideTheRealError() throws Exception {
        TestServer origin = server();
        origin.route("/data", (request, call) -> Response.of(500, "{}"));
        Interceptor faulty = new Interceptor() {
            @Override
            public void onError(Exception exception, HttpContext context) {
                throw new IllegalStateException("interceptor failed");
            }
        };
        PlainApi api = client(PlainApi.class, config(origin).addInterceptor(faulty).build());

        Exception failure = assertThrows(Exception.class, api::data);

        assertInstanceOf(ApiException.class, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, failure.getSuppressed()[0]);
    }

    @Test
    void aFailingOnResponseInterceptorNeitherFailsTheCallNorSkipsTheOthers() throws Exception {
        TestServer origin = server();
        origin.route("/data", (request, call) -> Response.of(200, "ok"));
        AtomicInteger seenByLater = new AtomicInteger();
        Interceptor faulty = new Interceptor() {
            @Override
            public void onResponse(HttpResponse response, HttpContext context) {
                throw new IllegalStateException("interceptor failed");
            }
        };
        Interceptor later = new Interceptor() {
            @Override
            public void onResponse(HttpResponse response, HttpContext context) {
                seenByLater.incrementAndGet();
            }
        };
        PlainApi api = client(PlainApi.class, config(origin).addInterceptor(faulty).addInterceptor(later).build());

        assertEquals("ok", api.data());

        assertEquals(1, seenByLater.get());
    }

    @Test
    void aFailingOnRequestInterceptorStillVetoesTheCallBeforeAnyNetworkTraffic() throws Exception {
        TestServer origin = server();
        origin.route("/data", (request, call) -> Response.of(200, "ok"));
        Interceptor veto = new Interceptor() {
            @Override
            public void onRequest(HttpRequest request, HttpContext context) {
                throw new IllegalStateException("not allowed");
            }
        };
        PlainApi api = client(PlainApi.class, config(origin).addInterceptor(veto).build());

        Exception failure = assertThrows(Exception.class, api::data);

        assertTrue(failure.toString().contains("not allowed"), failure.toString());
        assertEquals(0, origin.requestsTo("/data").size());
    }
}