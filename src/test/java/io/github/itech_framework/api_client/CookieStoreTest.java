package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.cookie.BasicClientCookie;
import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;

public class CookieStoreTest extends BaseClientTest {

    public interface SessionApi {
        @GET("/login")
        String login();

        @GET("/me")
        String me();
    }

    private TestServer sessionServer() throws Exception {
        TestServer server = server();
        server.route("/login", (request, call) -> Response.of(200, "in").withHeader("Set-Cookie", "session=abc; Path=/"));
        server.route("/me", (request, call) -> Response.of(200, "ok"));
        return server;
    }

    @Test
    void suppliedStoreCollectsAndSendsCookies() throws Exception {
        TestServer server = sessionServer();
        BasicCookieStore store = new BasicCookieStore();

        SessionApi api = client(SessionApi.class, config(server).cookieStore(store).build());
        api.login();
        api.me();

        assertEquals(1, store.getCookies().size());
        assertEquals("session", store.getCookies().get(0).getName());
        assertEquals("abc", store.getCookies().get(0).getValue());
        assertEquals("session=abc", server.requestsTo("/me").get(0).header("Cookie"));
    }

    @Test
    void clearingTheStoreStopsSendingTheCookie() throws Exception {
        TestServer server = sessionServer();
        BasicCookieStore store = new BasicCookieStore();

        SessionApi api = client(SessionApi.class, config(server).cookieStore(store).build());
        api.login();
        store.clear();
        api.me();

        assertNull(server.requestsTo("/me").get(0).header("Cookie"));
    }

    @Test
    void cookieAddedToTheStoreIsSent() throws Exception {
        TestServer server = sessionServer();
        BasicCookieStore store = new BasicCookieStore();
        BasicClientCookie cookie = new BasicClientCookie("token", "t1");
        cookie.setDomain("127.0.0.1");
        cookie.setPath("/");
        store.addCookie(cookie);

        SessionApi api = client(SessionApi.class, config(server).cookieStore(store).build());
        api.me();

        assertEquals("token=t1", server.requestsTo("/me").get(0).header("Cookie"));
    }
}