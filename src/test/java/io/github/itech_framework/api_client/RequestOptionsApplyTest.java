package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.SocketTimeoutException;
import java.net.URI;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Received;
import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.parameters.BaseUrl;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.utils.RequestOptions;
import io.github.itech_framework.api_client.utils.response.ApiResponse;

public class RequestOptionsApplyTest extends BaseClientTest {

    public interface OptionsApi {
        @GET("/slow")
        String slow(RequestOptions options);

        @GET("/redirect")
        ApiResponse<String> redirect(RequestOptions options);

        @GET("/hop/0")
        String chain(RequestOptions options);

        @GET("/x")
        String viaBase(RequestOptions options);

        @GET("")
        String at(@BaseUrl String url, RequestOptions options);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static int portOf(TestServer server) {
        return URI.create(server.url()).getPort();
    }

    private TestServer slowServer(long millis) throws Exception {
        TestServer server = server();
        server.route("/slow", (request, call) -> {
            Thread.sleep(millis);
            return Response.of(200, "late");
        });
        return server;
    }

    private TestServer redirectServer() throws Exception {
        TestServer server = server();
        server.route("/redirect",
                (request, call) -> Response.empty(302).withHeader("Location", server.url() + "/target"));
        server.route("/target", (request, call) -> Response.of(200, "done"));
        return server;
    }

    private TestServer chainServer(int length) throws Exception {
        TestServer server = server();
        server.route("/hop/", (request, call) -> {
            int step = Integer.parseInt(request.rawPath().substring("/hop/".length()));
            return step < length
                    ? Response.empty(302).withHeader("Location", server.url() + "/hop/" + (step + 1))
                    : Response.of(200, "end");
        });
        return server;
    }

    @Test
    void socketTimeoutOptionFailsASlowCall() throws Exception {
        TestServer server = slowServer(1500);
        OptionsApi api = client(OptionsApi.class, config(server).maxRetries(0).build());

        ApiException error = assertThrows(ApiException.class,
                () -> api.slow(RequestOptions.builder().socketTimeoutMillis(200).build()));

        assertEquals("IO_ERROR", error.getErrorCode());
        assertInstanceOf(SocketTimeoutException.class, rootCause(error));
    }

    @Test
    void socketTimeoutOptionCanBeLongerThanTheClientDefault() throws Exception {
        TestServer server = slowServer(500);
        OptionsApi api = client(OptionsApi.class, config(server).maxRetries(0).socketTimeout(200).build());

        assertEquals("late", api.slow(RequestOptions.builder().socketTimeoutMillis(5000).build()));
    }

    @Test
    void clientDefaultTimeoutStillAppliesWithoutOptions() throws Exception {
        TestServer server = slowServer(1500);
        OptionsApi api = client(OptionsApi.class, config(server).maxRetries(0).socketTimeout(200).build());

        assertThrows(ApiException.class, () -> api.slow(null));
    }

    @Test
    void followRedirectsFalseReturnsTheRedirectItself() throws Exception {
        TestServer server = redirectServer();
        OptionsApi api = client(OptionsApi.class, config(server).build());

        ApiResponse<String> response = api.redirect(RequestOptions.builder().followRedirects(false).build());

        assertEquals(302, response.status());
        assertEquals(server.url() + "/target", response.header("Location").orElseThrow());
        assertEquals(0, server.requestsTo("/target").size());
    }

    @Test
    void followRedirectsTrueOverridesAClientThatDoesNotFollow() throws Exception {
        TestServer server = redirectServer();
        OptionsApi api = client(OptionsApi.class, config(server).followRedirects(false).build());

        ApiResponse<String> response = api.redirect(RequestOptions.builder().followRedirects(true).build());

        assertEquals(200, response.status());
        assertEquals("done", response.body());
    }

    @Test
    void maxRedirectsOptionStopsALongChain() throws Exception {
        TestServer server = chainServer(10);
        OptionsApi api = client(OptionsApi.class, config(server).maxRetries(0).build());

        ApiException error = assertThrows(ApiException.class,
                () -> api.chain(RequestOptions.builder().maxRedirects(2).build()));

        assertEquals("IO_ERROR", error.getErrorCode());
        assertEquals(3, server.requests().size());
    }

    @Test
    void maxRedirectsOptionAllowsAChainThatFits() throws Exception {
        TestServer server = chainServer(3);
        OptionsApi api = client(OptionsApi.class, config(server).maxRetries(0).build());

        assertEquals("end", api.chain(RequestOptions.builder().maxRedirects(5).build()));
        assertEquals(4, server.requests().size());
    }

    @Test
    void proxyOptionSendsTheRequestThroughTheProxy() throws Exception {
        TestServer proxy = server();
        proxy.route("/", (request, call) -> Response.of(200, "via proxy"));
        OptionsApi api = client(OptionsApi.class, config(proxy).maxRetries(0).build());
        RequestOptions options = RequestOptions.builder().proxy("127.0.0.1", portOf(proxy)).build();

        String result = api.at("http://target.invalid/resource?x=1", options);

        assertEquals("via proxy", result);
        Received received = proxy.requests().get(0);
        assertEquals("target.invalid", received.header("Host"));
        assertEquals("/resource", received.rawPath());
        assertEquals("x=1", received.rawQuery());
    }

    @Test
    void proxyOptionDoesNotLeakIntoLaterCalls() throws Exception {
        TestServer proxy = server();
        proxy.route("/", (request, call) -> Response.of(200, "via proxy"));
        TestServer direct = server();
        direct.route("/", (request, call) -> Response.of(200, "direct"));
        OptionsApi api = client(OptionsApi.class, config(direct).maxRetries(0).build());
        RequestOptions options = RequestOptions.builder().proxy("127.0.0.1", portOf(proxy)).build();

        assertEquals("via proxy", api.viaBase(options));
        assertEquals("direct", api.viaBase(null));

        assertEquals(1, proxy.requests().size());
        assertEquals(1, direct.requests().size());
    }
}