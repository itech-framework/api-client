package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.OAuth2;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.exceptions.UnauthorizedException;
import io.github.itech_framework.api_client.utils.enums.AuthType;
import io.github.itech_framework.core.exceptions.FrameworkException;

public class OAuth2Test extends BaseClientTest {

    @Authenticated(AuthType.OAUTH2)
    @OAuth2(clientId = "cid-cache", clientSecret = "sec", tokenUrl = "http://127.0.0.1:18089/token")
    public interface CachedApi {
        @GET("/secure")
        String secure();
    }

    @Authenticated(AuthType.OAUTH2)
    @OAuth2(clientId = "cid-refresh", clientSecret = "sec", tokenUrl = "http://127.0.0.1:18090/token")
    public interface RefreshApi {
        @GET("/secure")
        String secure();
    }

    @Authenticated(AuthType.OAUTH2)
    @OAuth2(clientId = "cid-revoked", clientSecret = "sec", tokenUrl = "http://127.0.0.1:18091/token")
    public interface RevokedApi {
        @GET("/secure")
        String secure();
    }

    @Authenticated(AuthType.OAUTH2)
    @OAuth2(clientId = "cid-fail", clientSecret = "sec", tokenUrl = "http://127.0.0.1:18092/token")
    public interface FailingTokenApi {
        @GET("/secure")
        String secure();
    }

    private static Response token(int call) {
        return Response.json(200, "{\"access_token\":\"tok-" + call + "\",\"expires_in\":\"3600\"}");
    }

    @Test
    void fetchesTokenOnceAndReusesIt() throws Exception {
        TestServer tokens = server(18089);
        tokens.route("/token", (request, call) -> token(call));
        TestServer api = server();
        api.route("/secure", (request, call) -> Response.of(200, request.header("Authorization")));
        CachedApi client = client(CachedApi.class, config(api).build());

        assertEquals("Bearer tok-1", client.secure());
        assertEquals("Bearer tok-1", client.secure());
        assertEquals("Bearer tok-1", client.secure());

        assertEquals(1, tokens.requestsTo("/token").size());
        String form = tokens.requestsTo("/token").get(0).body();
        assertTrue(form.contains("grant_type=client_credentials"), form);
        assertTrue(form.contains("client_id=cid-cache"), form);
    }

    @Test
    void refreshesTokenAndRetriesOnceAfterUnauthorized() throws Exception {
        TestServer tokens = server(18090);
        tokens.route("/token", (request, call) -> token(call));
        TestServer api = server();
        api.route("/secure", (request, call) -> "Bearer tok-2".equals(request.header("Authorization"))
                ? Response.of(200, "ok")
                : Response.empty(401));
        RefreshApi client = client(RefreshApi.class, config(api).build());

        assertEquals("ok", client.secure());

        assertEquals(2, tokens.requestsTo("/token").size());
        assertEquals(2, api.requestsTo("/secure").size());
    }

    @Test
    void stopsAfterOneRefreshWhenServerKeepsRejecting() throws Exception {
        TestServer tokens = server(18091);
        tokens.route("/token", (request, call) -> token(call));
        TestServer api = server();
        api.route("/secure", (request, call) -> Response.empty(401));
        RevokedApi client = client(RevokedApi.class, config(api).build());

        assertThrows(UnauthorizedException.class, client::secure);

        assertEquals(2, tokens.requestsTo("/token").size());
        assertEquals(2, api.requestsTo("/secure").size());
    }

    @Test
    void tokenEndpointFailureSurfacesAsFrameworkException() throws Exception {
        TestServer tokens = server(18092);
        tokens.route("/token", (request, call) -> Response.empty(500));
        TestServer api = server();
        api.route("/secure", (request, call) -> Response.of(200, "ok"));
        FailingTokenApi client = client(FailingTokenApi.class, config(api).build());

        FrameworkException error = assertThrows(FrameworkException.class, client::secure);

        assertTrue(error.getMessage().contains("500"));
        assertEquals(0, api.requests().size());
    }
}