package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.OAuth2;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.utils.enums.AuthType;
import io.github.itech_framework.core.exceptions.FrameworkException;

public class OAuth2ErrorTest extends BaseClientTest {

    @Authenticated(AuthType.OAUTH2)
    @OAuth2(clientId = "cid-error", clientSecret = "sec", tokenUrl = "http://127.0.0.1:18093/token")
    public interface TokenErrorApi {
        @GET("/secure")
        String secure();
    }

    @Test
    void aFailedTokenRequestReportsTheServersErrorFields() throws Exception {
        TestServer tokens = server(18093);
        tokens.route("/token", (request, call) -> Response.json(400,
                "{\"error\":\"invalid_scope\",\"error_description\":\"scope required\"}"));
        TestServer api = server();
        api.route("/secure", (request, call) -> Response.of(200, "ok"));
        TokenErrorApi client = client(TokenErrorApi.class, config(api).build());

        FrameworkException failure = assertThrows(FrameworkException.class, client::secure);

        assertTrue(failure.getMessage().contains("400"), failure.getMessage());
        assertTrue(failure.getMessage().contains("invalid_scope"), failure.getMessage());
        assertTrue(failure.getMessage().contains("scope required"), failure.getMessage());
        assertEquals(0, api.requests().size());
    }
}