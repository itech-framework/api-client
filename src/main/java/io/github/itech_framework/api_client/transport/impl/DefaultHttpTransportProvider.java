package io.github.itech_framework.api_client.transport.impl;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;

import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.auth.HttpClientAwareAuthenticationProvider;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;

public class DefaultHttpTransportProvider implements HttpTransportProvider {
	private final ApiClientConfig config;
	private final PoolingHttpClientConnectionManager connectionManager;
	private final CloseableHttpClient defaultHttpClient;
	private final ConcurrentHashMap<AuthenticationProvider, CloseableHttpClient> clientCache = new ConcurrentHashMap<>();

	public DefaultHttpTransportProvider(ApiClientConfig config) {
		this.config = config;
		this.connectionManager = createConnectionManager(config);
		this.defaultHttpClient = buildHttpClient(null);
	}

	private PoolingHttpClientConnectionManager createConnectionManager(ApiClientConfig config) {
		PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
		cm.setMaxTotal(config.getMaxConnections());
		cm.setDefaultMaxPerRoute(config.getMaxConnections());
		return cm;
	}

	@Override
	public CloseableHttpClient createHttpClient() {
		return defaultHttpClient;
	}

	@Override
	public CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider customAuth) throws IOException {
		if (customAuth == null) {
			return defaultHttpClient.execute(request);
		}

		if (!(customAuth instanceof HttpClientAwareAuthenticationProvider)) {
			HttpContext context = new BasicHttpContext();
			customAuth.apply(request, context);
			return defaultHttpClient.execute(request);
		}

		CloseableHttpClient dedicatedClient = clientCache.computeIfAbsent(customAuth, this::buildHttpClient);

		HttpContext context = new BasicHttpContext();
		customAuth.apply(request, context);

		return dedicatedClient.execute(request);
	}

	private CloseableHttpClient buildHttpClient(AuthenticationProvider authProvider) {
		try {
			RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(config.getConnectTimeout())
					.setSocketTimeout(config.getSocketTimeout()).setRedirectsEnabled(config.isFollowRedirects())
					.build();

			HttpClientBuilder builder = HttpClients.custom().setConnectionManager(connectionManager)
					.setDefaultRequestConfig(requestConfig);

			if (!config.isSslVerification()) {
				SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, new TrustSelfSignedStrategy())
						.build();
				SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext,
						new String[] { "TLSv1.2", "TLSv1.3" }, null,
						SSLConnectionSocketFactory.getDefaultHostnameVerifier());
				builder.setSSLSocketFactory(sslSocketFactory);
			}

			if (authProvider instanceof HttpClientAwareAuthenticationProvider) {
				((HttpClientAwareAuthenticationProvider) authProvider).configureHttpClient(builder);
			}

			return builder.build();
		} catch (Exception e) {
			throw new RuntimeException("Failed to create HTTP client", e);
		}
	}

	@Override
	public void close() throws IOException {
		for (CloseableHttpClient client : clientCache.values()) {
			try {
				client.close();
			} catch (IOException ignored) {
			}
		}
		clientCache.clear();

		defaultHttpClient.close();
		connectionManager.close();
	}
}