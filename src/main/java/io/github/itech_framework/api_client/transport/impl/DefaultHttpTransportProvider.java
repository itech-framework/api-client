package io.github.itech_framework.api_client.transport.impl;

import java.io.IOException;

import javax.net.ssl.SSLContext;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;

import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;

public class DefaultHttpTransportProvider implements HttpTransportProvider {
    private final CloseableHttpClient httpClient;
    private final PoolingHttpClientConnectionManager connectionManager;
    
    public DefaultHttpTransportProvider(ApiClientConfig config) {
        this.connectionManager = createConnectionManager(config);
        this.httpClient = createHttpClient(config);
    }
    
    private PoolingHttpClientConnectionManager createConnectionManager(ApiClientConfig config) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(config.getMaxConnections());
        cm.setDefaultMaxPerRoute(config.getMaxConnections());
        return cm;
    }
    
    @Override
    public CloseableHttpClient createHttpClient() {
        return httpClient;
    }
    
    private CloseableHttpClient createHttpClient(ApiClientConfig config) {
        try {
            RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(config.getConnectTimeout())
                .setSocketTimeout(config.getSocketTimeout())
                .setRedirectsEnabled(config.isFollowRedirects())
                .build();
            
            if (config.isSslVerification()) {
                return HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .build();
            } else {
                SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, new TrustSelfSignedStrategy())
                    .build();
                
                SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                    sslContext,
                    new String[]{"TLSv1.2", "TLSv1.3"},
                    null,
                    SSLConnectionSocketFactory.getDefaultHostnameVerifier()
                );
                
                return HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setSSLSocketFactory(sslSocketFactory)
                    .setDefaultRequestConfig(requestConfig)
                    .build();
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create HTTP client", e);
        }
    }
    
    @Override
    public CloseableHttpResponse execute(HttpUriRequest request) throws IOException {
        return httpClient.execute(request);
    }
    
    @Override
    public void close() throws IOException {
        connectionManager.close();
        httpClient.close();
    }
}