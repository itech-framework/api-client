package io.github.itech_framework.api_client.transport.impl;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ProxySelector;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import org.apache.http.Header;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.conn.SystemDefaultRoutePlanner;
import org.apache.http.message.BasicHeader;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.auth.HttpClientAwareAuthenticationProvider;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.exceptions.RequestCancelledException;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;
import io.github.itech_framework.api_client.utils.RequestOptions;
import io.github.itech_framework.api_client.utils.response.RedirectHop;
import io.github.itech_framework.api_client.utils.response.SentRequest;

public class DefaultHttpTransportProvider implements HttpTransportProvider {
	private static final Logger logger = LogManager.getLogger(DefaultHttpTransportProvider.class);
	private static final Set<String> IDEMPOTENT_METHODS = Set.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE");
	private static final Set<Integer> RETRYABLE_STATUSES = Set.of(429, 502, 503, 504);

	private final ApiClientConfig config;
	private final PoolingHttpClientConnectionManager connectionManager;
	private final CloseableHttpClient defaultHttpClient;
	
	private final CookieStore cookieStore;
	
	private final ConcurrentHashMap<AuthenticationProvider, CloseableHttpClient> clientCache = new ConcurrentHashMap<>();

	public DefaultHttpTransportProvider(ApiClientConfig config) {
		this.config = config;
		this.connectionManager = createConnectionManager(config);
		this.cookieStore = config.isCookiesEnabled()
		        ? (config.getCookieStore() != null ? config.getCookieStore() : new BasicCookieStore())
		        : null;
		this.defaultHttpClient = buildHttpClient(null);
	}

	private PoolingHttpClientConnectionManager createConnectionManager(ApiClientConfig config) {
		try {
			
			if (!config.isSslVerification()) {
			    logger.warn("TLS certificate and hostname verification are DISABLED for {}", config.getBaseUrl());
			}
			
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
		RequestConfig requestConfig = baseRequestConfig();

		HttpClientBuilder builder = HttpClients.custom()
		        .setConnectionManager(connectionManager)
		        .setConnectionManagerShared(true)
		        .setDefaultRequestConfig(requestConfig)
		        .setRedirectStrategy(new SafeRedirectStrategy())
		        .disableAutomaticRetries();

		builder.setRoutePlanner(new SystemDefaultRoutePlanner(ProxySelector.getDefault()));

		if (cookieStore == null) {
		    builder.disableCookieManagement();
		} else {
		    builder.setDefaultCookieStore(cookieStore);
		}
		
		if (authProvider == null) {
		    builder.evictExpiredConnections().evictIdleConnections(30, TimeUnit.SECONDS);
		}

		if (authProvider instanceof HttpClientAwareAuthenticationProvider aware) {
		    aware.configureHttpClient(builder);
		}

		return builder.build();
	}
	
	private RequestConfig baseRequestConfig() {
		return RequestConfig.custom().setConnectTimeout(config.getConnectTimeout())
				.setSocketTimeout(config.getSocketTimeout()).setConnectionRequestTimeout(config.getConnectTimeout())
				.setRedirectsEnabled(config.isFollowRedirects())
				.setNormalizeUri(config.isNormalizeUri()).build();
	}

	private RequestConfig requestConfigFor(RequestOptions options) {
		RequestConfig.Builder builder = RequestConfig.copy(baseRequestConfig());
		if (options.getConnectTimeoutMillis() != null) {
			builder.setConnectTimeout(options.getConnectTimeoutMillis());
		}
		if (options.getSocketTimeoutMillis() != null) {
			builder.setSocketTimeout(options.getSocketTimeoutMillis());
		}
		if (options.getConnectionRequestTimeoutMillis() != null) {
			builder.setConnectionRequestTimeout(options.getConnectionRequestTimeoutMillis());
		}
		if (options.getFollowRedirects() != null) {
			builder.setRedirectsEnabled(options.getFollowRedirects());
		}
		if (options.getMaxRedirects() != null) {
			builder.setMaxRedirects(options.getMaxRedirects());
		}
		if (options.getProxy() != null) {
			builder.setProxy(options.getProxy());
		}
		return builder.build();
	}

	private void applyOptions(HttpUriRequest request, HttpContext context) {
		if (!(context.getAttribute(Interceptor.OPTIONS) instanceof RequestOptions options)) {
			return;
		}
		if (request instanceof HttpRequestBase base) {
			base.setConfig(requestConfigFor(options));
		} else {
			logger.warn("Request options ignored: {} cannot carry a request configuration",
					request.getClass().getName());
		}
	}

	@Override
	public CloseableHttpClient createHttpClient() {
		return defaultHttpClient;
	}

	@Override
	public CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider auth) throws IOException {
	    return execute(request, auth, new BasicHttpContext());
	}

	@Override
	public CloseableHttpResponse execute(HttpUriRequest request, AuthenticationProvider auth,
	        HttpContext context) throws IOException {
		CloseableHttpClient client = clientFor(auth);
		
		boolean repeatable = !(request instanceof HttpEntityEnclosingRequest enclosing)
		        || enclosing.getEntity() == null
		        || enclosing.getEntity().isRepeatable();
		int maxAttempts = IDEMPOTENT_METHODS.contains(request.getMethod()) && repeatable ? config.getMaxRetries() + 1 : 1;
		
		prepareAuth(request, auth);
		applyOptions(request, context);

		boolean reauthenticated = false;
		
		int attempt = 0;

		while (true) {
			
			if (request.isAborted()) {
				throw new RequestCancelledException();
			}
			
			attempt++;
			context.setAttribute(Interceptor.ATTEMPT, attempt);
			notifyBeforeSend(request, context);
			CloseableHttpResponse response;
			HttpClientContext attemptContext = HttpClientContext.create();
			context.removeAttribute(Interceptor.SENT_REQUEST);
			context.removeAttribute(Interceptor.REDIRECTS);
			try {
			    response = client.execute(request, attemptContext);
			} catch (RequestCancelledException e) {
			    throw e;
			} catch (IOException e) {
				
			    if (request.isAborted()) {
			        throw new RequestCancelledException();
			    }
				
			    if (attempt >= maxAttempts) {
			        throw e;
			    }
			    logger.debug("Retrying after I/O error (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
			    pause(backoffMillis(attempt, null));
			    continue;
			}

			int status = response.getStatusLine().getStatusCode();
			
			context.setAttribute(Interceptor.SENT_REQUEST, snapshot(attemptContext));
			context.setAttribute(Interceptor.REDIRECTS, attemptContext.getAttribute(Interceptor.REDIRECTS));

			if (status == 401 && auth != null && auth.retryOnUnauthorized() && !reauthenticated && repeatable) {
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
	
	private void notifyBeforeSend(HttpUriRequest request, HttpContext context) {
	    for (Interceptor interceptor : config.getInterceptors()) {
	        try {
	            interceptor.onBeforeSend(request, context);
	        } catch (RuntimeException failure) {
	            logger.warn("Interceptor {} failed in onBeforeSend", interceptor.getClass().getName(), failure);
	        }
	    }
	}
	
	private SentRequest snapshot(HttpClientContext attemptContext) {
	    HttpRequest sent = attemptContext.getRequest();
	    if (sent == null) {
	        return null;
	    }
	    Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	    for (Header header : sent.getAllHeaders()) {
	        headers.computeIfAbsent(header.getName(), name -> new ArrayList<>()).add(header.getValue());
	    }
	    return new SentRequest(sent.getRequestLine().getMethod(), absoluteUri(sent, attemptContext), headers);
	}

	private static String absoluteUri(HttpRequest request, HttpClientContext context) {
	    String target = request.getRequestLine().getUri();
	    HttpHost host = context.getTargetHost();
	    return host == null || target.startsWith("http://") || target.startsWith("https://")
	            ? target
	            : host.toURI() + target;
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
	
	final class SafeRedirectStrategy extends DefaultRedirectStrategy {
        private static final Set<String> CROSS_ORIGIN_ALLOWED = Set.of("accept", "accept-charset", "accept-language", "content-type", "user-agent");
	    @Override
	    public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context)
	            throws ProtocolException {
	        HttpUriRequest redirect = super.getRedirect(request, response, context);
	        HttpHost origin = HttpClientContext.adapt(context).getTargetHost();
	        URI target = redirect.getURI();
	        recordHop(context, response, absoluteUri(request, HttpClientContext.adapt(context)), target);
	        
	        boolean sameOrigin = origin != null && target.getHost() != null
	                && origin.getSchemeName().equalsIgnoreCase(target.getScheme())
	                && origin.getHostName().equalsIgnoreCase(target.getHost())
	                && originPort(origin) == port(target);
	        List<Header> kept = new ArrayList<>();
	        for (Header header : request.getAllHeaders()) {
	        	if (sameOrigin || CROSS_ORIGIN_ALLOWED.contains(header.getName().toLowerCase(Locale.ROOT))) {
	                kept.add(header);
	            }
	        }
	        if (kept.isEmpty()) {
	            kept.add(new BasicHeader(HttpHeaders.ACCEPT, "*/*"));
	        }
	        redirect.setHeaders(kept.toArray(new Header[0]));
	        return redirect;
	    }

	    private static int originPort(HttpHost host) {
	        if (host.getPort() >= 0) {
	            return host.getPort();
	        }
	        return "https".equalsIgnoreCase(host.getSchemeName()) ? 443 : 80;
	    }

	    @SuppressWarnings("unchecked")
	    private void recordHop(HttpContext context, HttpResponse response, String from, URI target) {
	        Object existing = context.getAttribute(Interceptor.REDIRECTS);
	        List<RedirectHop> hops = existing instanceof List<?> ? (List<RedirectHop>) existing : new ArrayList<>();
	        hops.add(new RedirectHop(response.getStatusLine().getStatusCode(), from, target.toString()));
	        context.setAttribute(Interceptor.REDIRECTS, hops);
	    }

	    private static int port(URI uri) {
	        if (uri.getPort() >= 0) {
	            return uri.getPort();
	        }
	        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
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