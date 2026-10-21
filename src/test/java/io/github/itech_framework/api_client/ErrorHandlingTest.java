package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.exceptions.BadRequestException;
import io.github.itech_framework.api_client.exceptions.ClientErrorException;
import io.github.itech_framework.api_client.exceptions.ConflictException;
import io.github.itech_framework.api_client.exceptions.ForbiddenException;
import io.github.itech_framework.api_client.exceptions.InternalServerErrorException;
import io.github.itech_framework.api_client.exceptions.NotFoundException;
import io.github.itech_framework.api_client.exceptions.ServerErrorException;
import io.github.itech_framework.api_client.exceptions.ServiceUnavailableException;
import io.github.itech_framework.api_client.exceptions.TooManyRequestsException;
import io.github.itech_framework.api_client.exceptions.UnauthorizedException;

public class ErrorHandlingTest extends BaseClientTest {

    public interface ErrorApi {
        @GET("/s400")
        String s400();

        @GET("/s401")
        String s401();

        @GET("/s403")
        String s403();

        @GET("/s404")
        String s404();

        @GET("/s409")
        String s409();

        @GET("/s418")
        String s418();

        @GET("/s429")
        String s429();

        @GET("/s500")
        String s500();

        @GET("/s502")
        String s502();

        @GET("/s503")
        String s503();

        @GET("/e1")
        String e1();

        @GET("/e2")
        String e2();

        @GET("/e3")
        String e3();

        @GET("/e4")
        String e4();
    }

    @Test
    void mapsStatusCodesToTypedExceptions() throws Exception {
        TestServer server = server();
        for (int code : new int[] { 400, 401, 403, 404, 409, 418, 429, 500, 502, 503 }) {
            server.route("/s" + code, (request, call) -> Response.json(code, "{\"code\":\"C" + code + "\"}"));
        }
        ErrorApi api = client(ErrorApi.class, config(server).maxRetries(0).build());

        assertThrows(BadRequestException.class, api::s400);
        assertThrows(UnauthorizedException.class, api::s401);
        assertThrows(ForbiddenException.class, api::s403);
        assertThrows(ConflictException.class, api::s409);
        assertThrows(TooManyRequestsException.class, api::s429);
        assertThrows(InternalServerErrorException.class, api::s500);
        assertThrows(ServiceUnavailableException.class, api::s503);

        ApiException teapot = assertThrows(ClientErrorException.class, api::s418);
        assertEquals(418, teapot.getStatusCode());

        ApiException badGateway = assertThrows(ServerErrorException.class, api::s502);
        assertEquals(502, badGateway.getStatusCode());
    }

    @Test
    void exposesStatusErrorCodeAndBody() throws Exception {
        TestServer server = server();
        server.route("/s404", (request, call) -> Response.json(404, "{\"code\":\"NOT_HERE\"}"));
        ErrorApi api = client(ErrorApi.class, config(server).maxRetries(0).build());

        ApiException error = assertThrows(NotFoundException.class, api::s404);

        assertEquals(404, error.getStatusCode());
        assertEquals("NOT_HERE", error.getErrorCode());
        assertTrue(error.getResponseBody().contains("NOT_HERE"));
    }

    @Test
    void extractsErrorCodesFromCommonShapes() throws Exception {
        TestServer server = server();
        server.route("/e1", (request, call) -> Response.json(400, "{\"error\":{\"code\":\"NESTED\"}}"));
        server.route("/e2", (request, call) -> Response.json(400, "{\"error\":\"PLAIN\"}"));
        server.route("/e3", (request, call) -> Response.json(400, "{\"errorCode\":\"ALT\"}"));
        server.route("/e4", (request, call) -> Response.of(400, "<html>bad</html>"));
        ErrorApi api = client(ErrorApi.class, config(server).maxRetries(0).build());

        assertEquals("NESTED", assertThrows(BadRequestException.class, api::e1).getErrorCode());
        assertEquals("PLAIN", assertThrows(BadRequestException.class, api::e2).getErrorCode());
        assertEquals("ALT", assertThrows(BadRequestException.class, api::e3).getErrorCode());
        assertEquals("UNKNOWN_ERROR", assertThrows(BadRequestException.class, api::e4).getErrorCode());
    }
}