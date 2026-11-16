package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Received;
import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.DELETE;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.HEAD;
import io.github.itech_framework.api_client.annotations.methods.OPTIONS;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.utils.response.ApiResponse;

public class BodyOnAllMethodsTest extends BaseClientTest {

    public static class Payload {
        public String name;

        public Payload() {
        }

        public Payload(String name) {
            this.name = name;
        }
    }

    public interface BodyApi {
        @GET("/get")
        String get(@Body Payload payload);

        @GET("/get")
        String getWithoutBody();

        @DELETE("/delete")
        String delete(@Body Payload payload);

        @OPTIONS("/options")
        String options(@Body Payload payload);

        @HEAD("/head")
        ApiResponse<Void> head(@Body Payload payload);

        @POST("/post")
        String post(@Body Payload payload);
    }

    private TestServer okServer() throws Exception {
        TestServer server = server();
        server.route("/", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    private BodyApi api(TestServer server) {
        return client(BodyApi.class, config(server).maxRetries(0).build());
    }

    @Test
    void getSendsBody() throws Exception {
        TestServer server = okServer();

        assertEquals("ok", api(server).get(new Payload("x")));

        Received received = server.requestsTo("/get").get(0);
        assertEquals("GET", received.method());
        assertTrue(received.body().contains("\"name\":\"x\""));
        assertTrue(received.header("Content-Type").startsWith("application/json"));
    }

    @Test
    void deleteSendsBody() throws Exception {
        TestServer server = okServer();

        assertEquals("ok", api(server).delete(new Payload("y")));

        Received received = server.requestsTo("/delete").get(0);
        assertEquals("DELETE", received.method());
        assertTrue(received.body().contains("\"name\":\"y\""));
        assertTrue(received.header("Content-Type").startsWith("application/json"));
    }

    @Test
    void optionsSendsBody() throws Exception {
        TestServer server = okServer();

        assertEquals("ok", api(server).options(new Payload("z")));

        Received received = server.requestsTo("/options").get(0);
        assertEquals("OPTIONS", received.method());
        assertTrue(received.body().contains("\"name\":\"z\""));
    }

    @Test
    void headNeverSendsBody() throws Exception {
        TestServer server = okServer();

        ApiResponse<Void> response = api(server).head(new Payload("h"));

        assertEquals(200, response.status());
        Received received = server.requestsTo("/head").get(0);
        assertEquals("HEAD", received.method());
        assertEquals("", received.body());
    }

    @Test
    void getWithoutBodyStaysPlain() throws Exception {
        TestServer server = okServer();

        assertEquals("ok", api(server).getWithoutBody());

        Received received = server.requestsTo("/get").get(0);
        assertEquals("GET", received.method());
        assertEquals("", received.body());
    }

    @Test
    void getWithNullBodyStaysPlain() throws Exception {
        TestServer server = okServer();

        assertEquals("ok", api(server).get(null));

        Received received = server.requestsTo("/get").get(0);
        assertEquals("GET", received.method());
        assertEquals("", received.body());
    }

    @Test
    void postBodyIsUnchanged() throws Exception {
        TestServer server = okServer();

        assertEquals("ok", api(server).post(new Payload("p")));

        Received received = server.requestsTo("/post").get(0);
        assertEquals("POST", received.method());
        assertTrue(received.body().contains("\"name\":\"p\""));
        assertNotNull(received.header("Content-Type"));
    }
}