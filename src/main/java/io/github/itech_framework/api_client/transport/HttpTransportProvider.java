package io.github.itech_framework.api_client.transport;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;

import io.github.itech_framework.api_client.auth.AuthenticationProvider;

import java.io.IOException;

public interface HttpTransportProvider {
    CloseableHttpClient createHttpClient();
    CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider customAuth) throws IOException;
    void close() throws IOException;
}