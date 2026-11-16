package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Received;
import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.annotations.parameters.Field;
import io.github.itech_framework.api_client.utils.body.BodyType;

public class RequestEncodingTest extends BaseClientTest {

    private static final String NON_ASCII = "\u00e9\u20ac";

    public static class Payload {
        public String name;

        public Payload() {
        }

        public Payload(String name) {
            this.name = name;
        }
    }

    public interface EncodingApi {
        @POST("/xml")
        String xml(@Body(type = BodyType.TEXT, contentType = "application/xml") String body);

        @POST("/xml-charset")
        String xmlCharset(@Body(type = BodyType.TEXT, contentType = "application/xml; charset=UTF-8") String body);

        @POST("/text")
        String text(@Body(type = BodyType.TEXT) String body);

        @POST("/latin")
        String latin(@Body(type = BodyType.TEXT, contentType = "text/plain; charset=ISO-8859-1") String body);

        @POST("/form-fields")
        String formFields(@Field("a") String a, @Field("b") String b);

        @POST("/form-exact")
        String formExact(
                @Body(type = BodyType.FORM, contentType = "application/x-www-form-urlencoded") Map<String, Object> body);

        @POST("/form-latin")
        String formLatin(
                @Body(type = BodyType.FORM, contentType = "application/x-www-form-urlencoded; charset=ISO-8859-1") Map<String, Object> body);

        @POST("/json")
        String json(@Body Payload payload);
    }

    private TestServer okServer() throws Exception {
        TestServer server = server();
        server.route("/", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    private EncodingApi api(TestServer server) {
        return client(EncodingApi.class, config(server).maxRetries(0).build());
    }

    @Test
    void xmlKeepsTheTypedContentTypeExactly() throws Exception {
        TestServer server = okServer();

        api(server).xml("<a>" + NON_ASCII + "</a>");

        Received received = server.requestsTo("/xml").get(0);
        assertEquals("application/xml", received.header("Content-Type"));
        assertEquals("<a>" + NON_ASCII + "</a>", received.body());
    }

    @Test
    void xmlKeepsAnExplicitCharset() throws Exception {
        TestServer server = okServer();

        api(server).xmlCharset("<a>" + NON_ASCII + "</a>");

        Received received = server.requestsTo("/xml-charset").get(0);
        assertEquals("application/xml; charset=UTF-8", received.header("Content-Type"));
        assertEquals("<a>" + NON_ASCII + "</a>", received.body());
    }

    @Test
    void textWithoutContentTypeGetsPlainUtf8() throws Exception {
        TestServer server = okServer();

        api(server).text(NON_ASCII);

        Received received = server.requestsTo("/text").get(0);
        assertEquals("text/plain; charset=UTF-8", received.header("Content-Type"));
        assertEquals(NON_ASCII, received.body());
    }

    @Test
    void textIsEncodedInTheDeclaredCharset() throws Exception {
        TestServer server = okServer();

        api(server).latin("\u00e9");

        Received received = server.requestsTo("/latin").get(0);
        assertEquals("text/plain; charset=ISO-8859-1", received.header("Content-Type"));
        assertEquals("1", received.header("Content-Length"));
    }

    @Test
    void fieldsWithoutContentTypeAreUrlEncodedAsUtf8() throws Exception {
        TestServer server = okServer();

        api(server).formFields("1", "\u00e9");

        Received received = server.requestsTo("/form-fields").get(0);
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", received.header("Content-Type"));
        assertEquals("a=1&b=%C3%A9", received.body());
    }

    @Test
    void formKeepsTheTypedContentTypeExactly() throws Exception {
        TestServer server = okServer();

        api(server).formExact(Map.of("a", "\u00e9"));

        Received received = server.requestsTo("/form-exact").get(0);
        assertEquals("application/x-www-form-urlencoded", received.header("Content-Type"));
        assertEquals("a=%C3%A9", received.body());
    }

    @Test
    void formIsEncodedInTheDeclaredCharset() throws Exception {
        TestServer server = okServer();

        api(server).formLatin(Map.of("a", "\u00e9"));

        Received received = server.requestsTo("/form-latin").get(0);
        assertEquals("application/x-www-form-urlencoded; charset=ISO-8859-1", received.header("Content-Type"));
        assertEquals("a=%E9", received.body());
    }

    @Test
    void defaultJsonCallIsUnchanged() throws Exception {
        TestServer server = okServer();

        api(server).json(new Payload("\u00e9"));

        Received received = server.requestsTo("/json").get(0);
        assertTrue(received.header("Content-Type").startsWith("application/json"));
        assertEquals("{\"name\":\"\u00e9\"}", received.body());
    }
}