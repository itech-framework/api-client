package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.parameters.Headers;
import io.github.itech_framework.api_client.interceptor.Interceptor;

public class RepeatedHeadersTest extends BaseClientTest {

    public static final class HeaderRecorder implements Interceptor {
        final List<String> multi = new CopyOnWriteArrayList<>();

        @Override
        public void onBeforeSend(HttpRequest request, HttpContext context) {
            for (Header header : request.getHeaders("X-Multi")) {
                multi.add(header.getValue());
            }
        }
    }

    public interface HeaderApi {
        @GET("/h")
        String withMap(@Headers Map<String, Object> headers);

        @GET("/h")
        String withPairs(@Headers List<NameValuePair> headers);

        @GET("/h")
        String withEntries(@Headers List<Map.Entry<String, String>> headers);

        @GET("/h")
        String withRaw(@Headers Object headers);
    }

    private TestServer okServer() throws Exception {
        TestServer server = server();
        server.route("/h", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    @Test
    void mapWithListValueSendsOneHeaderPerValue() throws Exception {
        TestServer server = okServer();
        HeaderRecorder recorder = new HeaderRecorder();
        HeaderApi api = client(HeaderApi.class, config(server).addInterceptor(recorder).build());

        api.withMap(Map.of("X-Multi", List.of("a", "b")));

        assertEquals(List.of("a", "b"), recorder.multi);
    }

    @Test
    void mapWithArrayValueSendsOneHeaderPerValue() throws Exception {
        TestServer server = okServer();
        HeaderRecorder recorder = new HeaderRecorder();
        HeaderApi api = client(HeaderApi.class, config(server).addInterceptor(recorder).build());

        api.withMap(Map.of("X-Multi", new String[] { "a", "b" }));

        assertEquals(List.of("a", "b"), recorder.multi);
    }

    @Test
    void listOfNameValuePairsSendsRepeatedHeaders() throws Exception {
        TestServer server = okServer();
        HeaderRecorder recorder = new HeaderRecorder();
        HeaderApi api = client(HeaderApi.class, config(server).addInterceptor(recorder).build());

        api.withPairs(List.of(new BasicNameValuePair("X-Multi", "a"), new BasicNameValuePair("X-Multi", "b")));

        assertEquals(List.of("a", "b"), recorder.multi);
    }

    @Test
    void listOfMapEntriesSendsRepeatedHeaders() throws Exception {
        TestServer server = okServer();
        HeaderRecorder recorder = new HeaderRecorder();
        HeaderApi api = client(HeaderApi.class, config(server).addInterceptor(recorder).build());

        api.withEntries(List.of(Map.entry("X-Multi", "a"), Map.entry("X-Multi", "b")));

        assertEquals(List.of("a", "b"), recorder.multi);
    }

    @Test
    void plainMapStillReachesTheServer() throws Exception {
        TestServer server = okServer();
        HeaderApi api = client(HeaderApi.class, config(server).build());

        api.withMap(Map.of("X-One", "1"));

        assertEquals("1", server.requestsTo("/h").get(0).header("X-One"));
    }

    @Test
    void nullHeaderValueIsSkipped() throws Exception {
        TestServer server = okServer();
        HeaderApi api = client(HeaderApi.class, config(server).build());
        Map<String, Object> headers = new HashMap<>();
        headers.put("X-Null", null);
        headers.put("X-Kept", "yes");

        api.withMap(headers);

        assertNull(server.requestsTo("/h").get(0).header("X-Null"));
        assertEquals("yes", server.requestsTo("/h").get(0).header("X-Kept"));
    }

    @Test
    void unsupportedHeadersArgumentIsRejected() throws Exception {
        TestServer server = okServer();
        HeaderApi api = client(HeaderApi.class, config(server).build());

        assertThrows(RuntimeException.class, () -> api.withRaw("not-a-map"));
        assertEquals(0, server.requestsTo("/h").size());
    }
}