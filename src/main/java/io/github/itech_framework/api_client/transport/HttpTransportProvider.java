package io.github.itech_framework.api_client.transport;

import java.io.IOException;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.protocol.HttpContext;

import io.github.itech_framework.api_client.auth.AuthenticationProvider;

public interface HttpTransportProvider {
    CloseableHttpClient createHttpClient();
    CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider customAuth) throws IOException;
    default CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider customAuth,
            HttpContext context) throws IOException {
        return execute(request, customAuth);
    }
    void close() throws IOException;
}