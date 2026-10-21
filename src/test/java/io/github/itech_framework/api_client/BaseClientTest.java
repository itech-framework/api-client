package io.github.itech_framework.api_client;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;

import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.handlers.DynamicApiClientInvocationHandler;

public abstract class BaseClientTest {
    private final List<TestServer> servers = new ArrayList<>();
    private final List<Object> clients = new ArrayList<>();

    protected TestServer server() throws IOException {
        return server(0);
    }

    protected TestServer server(int port) throws IOException {
        TestServer server = TestServer.start(port);
        servers.add(server);
        return server;
    }

    protected TestServer httpsServer(SSLContext context) throws IOException {
        TestServer server = TestServer.startHttps(context);
        servers.add(server);
        return server;
    }

    protected ApiClientConfig.Builder config(TestServer server) {
        return ApiClientConfig.builder().baseUrl(server.url()).maxRetries(2).retryBackoffMillis(1)
                .retryMaxBackoffMillis(5);
    }

    protected <T> T client(Class<T> api, ApiClientConfig config) {
        Object proxy = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[] { api },
                new DynamicApiClientInvocationHandler(api, config));
        clients.add(proxy);
        return api.cast(proxy);
    }

    @AfterEach
    protected void closeResources() {
        for (Object client : clients) {
            ((DynamicApiClientInvocationHandler) Proxy.getInvocationHandler(client)).close();
        }
        clients.clear();
        for (TestServer server : servers) {
            server.close();
        }
        servers.clear();
    }
}