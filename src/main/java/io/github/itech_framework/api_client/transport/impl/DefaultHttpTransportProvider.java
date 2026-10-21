package io.github.itech_framework.api_client.transport.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.security.GeneralSecurityException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.auth.HttpClientAwareAuthenticationProvider;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;

public class DefaultHttpTransportProvider implements HttpTransportProvider {
	private static final Logger logger = LogManager.getLogger(DefaultHttpTransportProvider.class);
	private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");
	private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

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
		try {
			SSLContext sslContext = config.isSslVerification() ? SSLContexts.createDefault()
					: SSLContexts.custom().loadTrustMaterial(null, (chain, authType) -> true).build();
			HostnameVerifier verifier = config.isSslVerification()
					? SSLConnectionSocketFactory.getDefaultHostnameVerifier()
					: NoopHostnameVerifier.INSTANCE;
			Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
					.register("http", PlainConnectionSocketFactory.getSocketFactory())
					.register("https", new SSLConnectionSocketFactory(sslContext,
							new String[] { "TLSv1.2", "TLSv1.3" }, null, verifier))
					.build();
			PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager(registry);
			manager.setMaxTotal(config.getMaxConnections());
			manager.setDefaultMaxPerRoute(config.getMaxConnections());
			manager.setValidateAfterInactivity(2000);
			return manager;
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Failed to create HTTP connection manager", e);
		}
	}

	private CloseableHttpClient buildHttpClient(AuthenticationProvider authProvider) {
		RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(config.getConnectTimeout())
				.setSocketTimeout(config.getSocketTimeout()).setConnectionRequestTimeout(config.getConnectTimeout())
				.setRedirectsEnabled(config.isFollowRedirects()).build();

		HttpClientBuilder builder = HttpClients.custom().setConnectionManager(connectionManager)
				.setConnectionManagerShared(true).setDefaultRequestConfig(requestConfig).disableAutomaticRetries();

		if (authProvider == null) {
			builder.evictExpiredConnections().evictIdleConnections(30, TimeUnit.SECONDS);
		}

		if (authProvider instanceof HttpClientAwareAuthenticationProvider aware) {
			aware.configureHttpClient(builder);
		}

		return builder.build();
	}

	@Override
	public CloseableHttpClient createHttpClient() {
		return defaultHttpClient;
	}

	@Override
	public CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider auth) throws IOException {
		CloseableHttpClient client = clientFor(auth);
		prepareAuth(request, auth);

		int maxAttempts = IDEMPOTENT_METHODS.contains(request.getMethod()) ? config.getMaxRetries() + 1 : 1;
		boolean reauthenticated = false;
		int attempt = 0;

		while (true) {
			attempt++;
			CloseableHttpResponse response;
			try {
				response = client.execute(request);
			} catch (IOException e) {
				if (attempt >= maxAttempts) {
					throw e;
				}
				logger.debug("Retrying after I/O error (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
				pause(backoffMillis(attempt, null));
				continue;
			}

			int status = response.getStatusLine().getStatusCode();

			if (status == 401 && auth != null && auth.retryOnUnauthorized() && !reauthenticated) {
				reauthenticated = true;
				discard(response);
				auth.refresh();
				prepareAuth(request, auth);
				attempt--;
				continue;
			}

			if (attempt < maxAttempts && RETRYABLE_STATUSES.contains(status)) {
				long delay = backoffMillis(attempt, response);
				logger.debug("Retrying after status {} (attempt {}/{}) in {} ms", status, attempt, maxAttempts, delay);
				discard(response);
				pause(delay);
				continue;
			}

			return response;
		}
	}

	private CloseableHttpClient clientFor(AuthenticationProvider auth) {
		if (auth instanceof HttpClientAwareAuthenticationProvider) {
			return clientCache.computeIfAbsent(auth, this::buildHttpClient);
		}
		return defaultHttpClient;
	}

	private void prepareAuth(HttpUriRequest request, AuthenticationProvider auth) {
		if (auth == null) {
			return;
		}
		if (auth.isExpired()) {
			auth.refresh();
		}
		auth.apply(request, new BasicHttpContext());
	}

	private long backoffMillis(int attempt, HttpResponse response) {
		long maxBackoff = config.getRetryMaxBackoffMillis();
		long retryAfter = response == null ? -1 : retryAfterMillis(response);
		if (retryAfter >= 0) {
			return Math.min(retryAfter, maxBackoff);
		}
		long base = config.getRetryBackoffMillis();
		long exponential = base * (1L << Math.min(attempt - 1, 16));
		return Math.min(exponential, maxBackoff) + ThreadLocalRandom.current().nextLong(base + 1);
	}

	private long retryAfterMillis(HttpResponse response) {
		Header header = response.getFirstHeader("Retry-After");
		if (header == null || header.getValue() == null) {
			return -1;
		}
		try {
			return Long.parseLong(header.getValue().trim()) * 1000L;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private void pause(long millis) throws InterruptedIOException {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException("Interrupted while waiting to retry");
		}
	}

	private void discard(CloseableHttpResponse response) {
		EntityUtils.consumeQuietly(response.getEntity());
		try {
			response.close();
		} catch (IOException e) {
			logger.debug("Error closing response", e);
		}
	}

	@Override
	public void close() throws IOException {
		for (CloseableHttpClient client : clientCache.values()) {
			try {
				client.close();
			} catch (IOException e) {
				logger.warn("Error closing HTTP client", e);
			}
		}
		clientCache.clear();

		try {
			defaultHttpClient.close();
		} finally {
			connectionManager.close();
		}
	}
}