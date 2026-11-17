package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.parameters.Path;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.utils.CancelToken;
import io.github.itech_framework.api_client.utils.RequestOptions;

public class RequestOptionsParameterTest extends BaseClientTest {

    public static final class OptionsRecorder implements Interceptor {
        final List<Object> seen = new CopyOnWriteArrayList<>();

        @Override
        public void onRequest(HttpRequest request, HttpContext context) {
            seen.add(context.getAttribute(Interceptor.OPTIONS));
        }
    }

    public interface OptionsApi {
        @GET("/ping")
        String ping(RequestOptions options);

        @GET("/plain")
        String plain();

        @GET("/items/{id}")
        String item(@Path("id") String id, RequestOptions options, CancelToken token);
    }

    private TestServer okServer() throws Exception {
        TestServer server = server();
        server.route("/", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    @Test
    void optionsAreAvailableToInterceptors() throws Exception {
        TestServer server = okServer();
        OptionsRecorder recorder = new OptionsRecorder();
        OptionsApi api = client(OptionsApi.class, config(server).addInterceptor(recorder).build());
        RequestOptions options = RequestOptions.builder().timeoutMillis(1000).build();

        assertEquals("ok", api.ping(options));

        assertSame(options, recorder.seen.get(0));
    }

    @Test
    void nullOptionsLeaveNoAttribute() throws Exception {
        TestServer server = okServer();
        OptionsRecorder recorder = new OptionsRecorder();
        OptionsApi api = client(OptionsApi.class, config(server).addInterceptor(recorder).build());

        assertEquals("ok", api.ping(null));

        assertNull(recorder.seen.get(0));
    }

    @Test
    void methodWithoutTheParameterLeavesNoAttribute() throws Exception {
        TestServer server = okServer();
        OptionsRecorder recorder = new OptionsRecorder();
        OptionsApi api = client(OptionsApi.class, config(server).addInterceptor(recorder).build());

        assertEquals("ok", api.plain());

        assertNull(recorder.seen.get(0));
    }

    @Test
    void optionsWorkNextToOtherParameters() throws Exception {
        TestServer server = okServer();
        OptionsRecorder recorder = new OptionsRecorder();
        OptionsApi api = client(OptionsApi.class, config(server).addInterceptor(recorder).build());
        RequestOptions options = RequestOptions.builder().followRedirects(false).build();

        assertEquals("ok", api.item("42", options, new CancelToken()));

        assertEquals(1, server.requestsTo("/items/42").size());
        assertSame(options, recorder.seen.get(0));
    }
}