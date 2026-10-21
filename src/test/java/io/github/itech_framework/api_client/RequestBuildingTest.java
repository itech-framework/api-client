package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.DELETE;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.Header;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.parameters.BaseUrl;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.annotations.parameters.Headers;
import io.github.itech_framework.api_client.annotations.parameters.Path;
import io.github.itech_framework.api_client.annotations.parameters.Query;
import io.github.itech_framework.api_client.exceptions.ApiException;

public class RequestBuildingTest extends BaseClientTest {

    public static class User {
        public int id;
        public String name;

        public User() {
        }

        public User(int id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public interface Api {
        @GET("/users/{id}")
        String user(@Path("id") String id);

        @GET("/search")
        String search(@Query("tag") List<String> tags, @Query("q") String q, @Query("skip") String skip);

        @GET("/h")
        @Header(name = "X-Static", value = "one")
        String staticHeader();

        @GET("/h")
        String mapHeaders(@Headers Map<String, String> headers);

        @POST("/echo")
        String echo(@Body User user);

        @GET("/list")
        List<User> list();

        @GET("/one")
        User one();

        @GET("/bytes")
        byte[] bytes();

        @GET("/stream")
        InputStream stream();

        @DELETE("/gone/{id}")
        void delete(@Path("id") int id);

        @GET("/ping")
        String ping();

        @GET("/ping")
        String pingAt(@BaseUrl String baseUrl);

        @GET("/trailing/")
        String trailing();

        default String describe() {
            return "api:" + ping();
        }
    }

    @Test
    void encodesSpacesInPathValues() throws Exception {
        TestServer server = server();
        server.route("/users", (request, call) -> Response.of(200, "ok"));
        Api api = client(Api.class, config(server).build());

        assertEquals("ok", api.user("a b"));
        assertEquals("/users/a%20b", server.requests().get(0).rawPath());
    }

    @Test
    void slashInPathValueStaysInsideOneSegment() throws Exception {
        TestServer server = server();
        server.route("/users", (request, call) -> Response.of(200, "ok"));
        Api api = client(Api.class, config(server).build());

        api.user("a/b");

        assertEquals("/users/a%2Fb", server.requests().get(0).rawPath());
    }

    @Test
    void expandsListQueryParamsInOrderAndSkipsNulls() throws Exception {
        TestServer server = server();
        server.route("/search", (request, call) -> Response.of(200, "ok"));
        Api api = client(Api.class, config(server).build());

        api.search(List.of("a", "b"), "x", null);

        assertEquals("tag=a&tag=b&q=x", server.requests().get(0).rawQuery());
    }

    @Test
    void appliesMethodLevelStaticHeader() throws Exception {
        TestServer server = server();
        server.route("/h", (request, call) -> Response.of(200, "ok"));
        Api api = client(Api.class, config(server).build());

        api.staticHeader();

        assertEquals("one", server.requests().get(0).header("X-Static"));
    }

    @Test
    void appliesHeadersFromMapParameter() throws Exception {
        TestServer server = server();
        server.route("/h", (request, call) -> Response.of(200, "ok"));
        Api api = client(Api.class, config(server).build());

        api.mapHeaders(Map.of("X-One", "1"));

        assertEquals("1", server.requests().get(0).header("X-One"));
    }

    @Test
    void serializesBodyAsJson() throws Exception {
        TestServer server = server();
        server.route("/echo", (request, call) -> Response.json(200, request.body()));
        Api api = client(Api.class, config(server).build());

        String echoed = api.echo(new User(7, "Zaw"));

        TestServer.Received received = server.requests().get(0);
        assertTrue(received.header("Content-Type").startsWith("application/json"));
        assertTrue(received.body().contains("\"id\":7"));
        assertTrue(received.body().contains("\"name\":\"Zaw\""));
        assertEquals(received.body(), echoed);
    }

    @Test
    void parsesGenericListResponses() throws Exception {
        TestServer server = server();
        server.route("/list", (request, call) -> Response.json(200,
                "[{\"id\":1,\"name\":\"a\"},{\"id\":2,\"name\":\"b\",\"extra\":true}]"));
        Api api = client(Api.class, config(server).build());

        List<User> users = api.list();

        assertEquals(2, users.size());
        assertEquals("b", users.get(1).name);
    }

    @Test
    void returnsNullForEmptyBody() throws Exception {
        TestServer server = server();
        server.route("/one", (request, call) -> Response.empty(200));
        Api api = client(Api.class, config(server).build());

        assertNull(api.one());
    }

    @Test
    void reportsJsonParseErrorsWithStatusAndBodySnippet() throws Exception {
        TestServer server = server();
        server.route("/one", (request, call) -> Response.json(200, "not json"));
        Api api = client(Api.class, config(server).build());

        ApiException error = assertThrows(ApiException.class, api::one);

        assertEquals("JSON_PARSE_ERROR", error.getErrorCode());
        assertEquals(200, error.getStatusCode());
        assertEquals("not json", error.getResponseBody());
    }

    @Test
    void supportsByteArrayAndInputStreamResults() throws Exception {
        TestServer server = server();
        server.route("/bytes", (request, call) -> Response.of(200, "abc"));
        server.route("/stream", (request, call) -> Response.of(200, "streamed"));
        Api api = client(Api.class, config(server).build());

        assertArrayEquals("abc".getBytes(StandardCharsets.UTF_8), api.bytes());
        assertEquals("streamed", new String(api.stream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void voidMethodsAcceptNoContent() throws Exception {
        TestServer server = server();
        server.route("/gone", (request, call) -> Response.empty(204));
        Api api = client(Api.class, config(server).build());

        api.delete(5);

        assertEquals("DELETE", server.requests().get(0).method());
        assertEquals("/gone/5", server.requests().get(0).rawPath());
    }

    @Test
    void defaultMethodsAreInvokedNotProxied() throws Exception {
        TestServer server = server();
        server.route("/ping", (request, call) -> Response.of(200, "pong"));
        Api api = client(Api.class, config(server).build());

        assertEquals("api:pong", api.describe());
    }

    @Test
    void combinesBasePathWithMethodPath() throws Exception {
        TestServer server = server();
        server.route("/api", (request, call) -> Response.of(200, "pong"));
        Api api = client(Api.class, config(server).baseUrl(server.url() + "/api/v1/").build());

        api.ping();

        assertEquals("/api/v1/ping", server.requests().get(0).rawPath());
    }

    @Test
    void preservesTrailingSlash() throws Exception {
        TestServer server = server();
        server.route("/trailing", (request, call) -> Response.of(200, "ok"));
        Api api = client(Api.class, config(server).build());

        api.trailing();

        assertEquals("/trailing/", server.requests().get(0).rawPath());
    }

    @Test
    void baseUrlParameterOverridesConfiguredBaseUrl() throws Exception {
        TestServer server = server();
        server.route("/ping", (request, call) -> Response.of(200, "pong"));
        Api api = client(Api.class, config(server).baseUrl("http://127.0.0.1:1").build());

        assertEquals("pong", api.pingAt(server.url()));
    }
}