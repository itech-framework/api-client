package io.github.itech_framework.api_client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.itech_framework.api_client.TestServer.Response;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.config.ApiClientConfig;

public class ProxyTest extends BaseClientTest {

    public interface HelloApi {
        @GET("/hello")
        String hello();
    }

    @Test
    void requestsGoThroughTheJvmDefaultProxy() throws Exception {
        TestServer proxy = server();
        proxy.route("/hello", (request, call) -> Response.of(200, "via-proxy"));
        int proxyPort = URI.create(proxy.url()).getPort();
        ProxySelector original = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", proxyPort)));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            }
        });
        try {
            HelloApi api = client(HelloApi.class,
                    ApiClientConfig.builder().baseUrl("http://example.invalid").maxRetries(0).build());

            assertEquals("via-proxy", api.hello());
            assertEquals("example.invalid", proxy.requests().get(0).header("Host"));
        } finally {
            ProxySelector.setDefault(original);
        }
    }
}