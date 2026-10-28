package io.github.itech_framework.api_client.handlers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.itech_framework.api_client.auth.AuthProviderFactory;
import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;
import io.github.itech_framework.api_client.transport.impl.DefaultHttpTransportProvider;
import io.github.itech_framework.api_client.utils.JsonUtils;
import io.github.itech_framework.api_client.utils.body.BodyEncoders;
import io.github.itech_framework.core.exceptions.FrameworkException;

public class DynamicApiClientInvocationHandler implements InvocationHandler {
	private static final Logger logger = LogManager.getLogger(DynamicApiClientInvocationHandler.class);

	private final Class<?> apiInterface;
	private final ApiClientConfig config;
	private final HttpTransportProvider transportProvider;
	private final ExecutorService executorService;
	private final AuthProviderFactory authFactory;
	private final ErrorDecoder errorDecoder = new ErrorDecoder();
	private final BodyEncoders bodyEncoders = BodyEncoders.defaults();
	private final Map<Method, MethodHandler> methodHandlers = new ConcurrentHashMap<>();

	private record PreparedRequest(HttpUriRequest request, AuthenticationProvider auth) {
	}

	public DynamicApiClientInvocationHandler(Class<?> apiInterface, ApiClientConfig config) {
		this.apiInterface = apiInterface;
		this.config = config;
		this.authFactory = new AuthProviderFactory(apiInterface);
		this.transportProvider = config.getHttpTransportProvider() != null ? config.getHttpTransportProvider()
				: new DefaultHttpTransportProvider(config);
		this.executorService = config.isEnableAsync() ? createExecutor(apiInterface, config.getAsyncThreadPoolSize())
				: null;

		initializeMethodHandlers();

		if (config.isEnableLogging()) {
			logger.info("Initialized API client for interface: {} with config: {}", apiInterface.getName(), config);
		}
	}

	private static ExecutorService createExecutor(Class<?> apiInterface, int requestedSize) {
		int size = Math.max(1, requestedSize);
		AtomicInteger counter = new AtomicInteger();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(size * 100), runnable -> {
					Thread thread = new Thread(runnable,
							"api-client-" + apiInterface.getSimpleName() + "-" + counter.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, new ThreadPoolExecutor.CallerRunsPolicy());
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	private void initializeMethodHandlers() {
		for (Method method : apiInterface.getMethods()) {
			if (method.isDefault() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			methodHandlers.put(method, new MethodHandler(method));
			if (config.isEnableLogging()) {
				logger.debug("Registered method handler for: {}.{}", apiInterface.getSimpleName(), method.getName());
			}
		}
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) {
			return handleObjectMethod(proxy, method, args);
		}
		if (method.isDefault()) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		MethodHandler handler = methodHandlers.computeIfAbsent(method, MethodHandler::new);
		return handler.invoke(args == null ? new Object[0] : args);
	}

	private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
		case "toString" -> String.format("DynamicApiClientProxy[%s@%s]", apiInterface.getName(),
				Integer.toHexString(System.identityHashCode(proxy)));
		case "hashCode" -> System.identityHashCode(proxy);
		case "equals" -> proxy == args[0];
		default -> throw new UnsupportedOperationException("Unsupported Object method: " + method.getName());
		};
	}

	private class MethodHandler {
		private final Method method;
		private final MethodMetadata metadata;
		private final AuthenticationProvider staticAuth;
		private final BodyResolver bodyResolver;

		MethodHandler(Method method) {
			this.method = method;
			this.metadata = MethodMetadata.fromMethod(method, config.getBaseUrl());
			this.staticAuth = authFactory.resolve(method);
			this.bodyResolver = new BodyResolver(method);
		}

		Object invoke(Object[] args) throws Exception {
			if (!metadata.isAsync()) {
				return invokeSync(args);
			}
			if (config.isEnableAsync() && executorService != null) {
				return invokeAsync(args);
			}
			try {
				return CompletableFuture.completedFuture(invokeSync(args));
			} catch (Exception e) {
				return CompletableFuture.failedFuture(e);
			}
		}

		private Object invokeSync(Object[] args) throws Exception {
			try {
				PreparedRequest prepared = buildRequest(args);
				HttpContext context = createContext();
				if (config.isEnableLogging() && logger.isDebugEnabled()) {
					logger.debug("Executing {}", describe(prepared.request()));
				}
				return executeRequest(prepared, context);
			} catch (IOException e) {
				throw translate(e);
			}
		}

		private CompletableFuture<Object> invokeAsync(Object[] args) {
			return CompletableFuture.supplyAsync(() -> {
				try {
					return invokeSync(args);
				} catch (Exception e) {
					throw new CompletionException(e);
				}
			}, executorService);
		}

		private Exception translate(IOException e) {
			for (Class<?> declared : method.getExceptionTypes()) {
				if (declared.isAssignableFrom(e.getClass())) {
					return e;
				}
			}
			return new ApiException("I/O error while calling " + apiInterface.getSimpleName() + "." + method.getName(),
					0, null, "IO_ERROR", e);
		}

		private PreparedRequest buildRequest(Object[] args) throws Exception {
			String effectiveBaseUrl = metadata.extractBaseUrl(args);
			if (effectiveBaseUrl == null || effectiveBaseUrl.isBlank()) {
				effectiveBaseUrl = metadata.getFullUrl();
			}
			if (effectiveBaseUrl == null || effectiveBaseUrl.isBlank()) {
				throw new FrameworkException("Base url is not defined for " + apiInterface.getName()
						+ ". Use @ApiClient(baseUrl = \"...\"), the property api.client."
						+ apiInterface.getSimpleName().toLowerCase()
						+ ".baseUrl, the property flexi.api.baseUrl, or a @BaseUrl String parameter.");
			}

			URIBuilder uriBuilder = new URIBuilder(effectiveBaseUrl);
			List<String> segments = new ArrayList<>();
			String basePath = uriBuilder.getPath();
			if (basePath != null) {
				for (String segment : basePath.split("/")) {
					if (!segment.isEmpty()) {
						segments.add(segment);
					}
				}
			}
			segments.addAll(metadata.resolvePathSegments(args));
			uriBuilder.setPathSegments(segments);

			List<NameValuePair> queryParams = metadata.extractQueryParams(args);
			if (!queryParams.isEmpty()) {
				uriBuilder.addParameters(queryParams);
			}

			HttpUriRequest request = createHttpRequest(uriBuilder.build());
			for (NameValuePair header : metadata.extractHeaders(args)) {
				request.addHeader(header.getName(), header.getValue());
			}
			applyBody(request, args);
			applyDefaultHeaders(request);

			AuthenticationProvider auth = metadata.extractAuth(args);
			if (auth == null) {
				auth = staticAuth;
			}
			if (auth == null) {
				auth = config.getAuthenticationProvider();
			}
			return new PreparedRequest(request, auth);
		}

		private HttpContext createContext() {
			HttpContext context = new BasicHttpContext();
			context.setAttribute("method", method);
			context.setAttribute("config", config);
			context.setAttribute("timestamp", System.currentTimeMillis());
			return context;
		}

		private Object executeRequest(PreparedRequest prepared, HttpContext context) throws Exception {
			for (Interceptor interceptor : config.getInterceptors()) {
				interceptor.onRequest(prepared.request(), context);
			}
			try (CloseableHttpResponse response = transportProvider.execute(prepared.request(), prepared.auth())) {
				for (Interceptor interceptor : config.getInterceptors()) {
					interceptor.onResponse(response, context);
				}
				return processResponse(response);
			} catch (Exception e) {
				for (Interceptor interceptor : config.getInterceptors()) {
					interceptor.onError(e, context);
				}
				throw e;
			}
		}

		private void applyDefaultHeaders(HttpRequest request) {
			for (Map.Entry<String, String> entry : config.getDefaultHeaders().entrySet()) {
				if (!request.containsHeader(entry.getKey())) {
					request.addHeader(entry.getKey(), entry.getValue());
				}
			}
		}

		/**
		 * Builds the request entity from @Body, @Part or @Field parameters.
		 *
		 * Content type precedence: @Body(contentType) > Content-Type header from @Header/@Headers >
		 * default of the resolved body type. The entity's own Content-Type always replaces the header, so
		 * multipart requests get a valid generated boundary.
		 */
		private void applyBody(HttpUriRequest request, Object[] args) throws IOException {
			if (!(request instanceof HttpEntityEnclosingRequest enclosing)) {
				return;
			}
			BodyResolver.Resolved resolved = bodyResolver.resolve(args);
			if (resolved == null) {
				return;
			}

			String contentType = resolved.contentType();
			if (contentType == null || contentType.isBlank()) {
				Header header = request.getFirstHeader("Content-Type");
				contentType = header == null ? null : header.getValue();
			}

			HttpEntity entity = bodyEncoders.encode(resolved.value(), resolved.type(), contentType);

			request.removeHeaders("Content-Type");
			if (entity.getContentType() != null) {
				request.setHeader(entity.getContentType());
			}
			enclosing.setEntity(entity);
		}

		private HttpUriRequest createHttpRequest(URI uri) {
			return switch (metadata.getHttpMethod()) {
			case "GET" -> new HttpGet(uri);
			case "POST" -> new HttpPost(uri);
			case "PUT" -> new HttpPut(uri);
			case "DELETE" -> new HttpDelete(uri);
			case "PATCH" -> new HttpPatch(uri);
			case "HEAD" -> new HttpHead(uri);
			case "OPTIONS" -> new HttpOptions(uri);
			default -> throw new IllegalArgumentException("Unsupported HTTP method: " + metadata.getHttpMethod());
			};
		}

		private Object processResponse(CloseableHttpResponse response) throws IOException {
			int status = response.getStatusLine().getStatusCode();
			HttpEntity entity = response.getEntity();

			if (status >= 400) {
				String errorBody = entity == null ? null : EntityUtils.toString(entity, charsetOf(entity));
				throw errorDecoder.decode(status, errorBody);
			}

			Class<?> resultClass = metadata.getResultClass();
			if (resultClass == void.class || resultClass == Void.class) {
				EntityUtils.consumeQuietly(entity);
				return null;
			}
			if (entity == null) {
				return null;
			}
			if (resultClass == String.class) {
				return EntityUtils.toString(entity, charsetOf(entity));
			}
			byte[] bytes = EntityUtils.toByteArray(entity);
			if (resultClass == byte[].class) {
				return bytes;
			}
			if (resultClass == InputStream.class) {
				return new ByteArrayInputStream(bytes);
			}
			if (isBlank(bytes)) {
				return null;
			}
			try {
				return JsonUtils.fromJson(bytes, metadata.getResultJavaType());
			} catch (FrameworkException e) {
				String snippet = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8);
				throw new ApiException("Failed to parse JSON response", status, snippet, "JSON_PARSE_ERROR", e);
			}
		}
	}

	private static boolean isBlank(byte[] bytes) {
		for (byte value : bytes) {
			if (value != ' ' && value != '\n' && value != '\r' && value != '\t') {
				return false;
			}
		}
		return true;
	}

	private static Charset charsetOf(HttpEntity entity) {
		try {
			ContentType contentType = ContentType.get(entity);
			if (contentType != null && contentType.getCharset() != null) {
				return contentType.getCharset();
			}
		} catch (ParseException | UnsupportedCharsetException e) {
			return StandardCharsets.UTF_8;
		}
		return StandardCharsets.UTF_8;
	}

	private static String describe(HttpUriRequest request) {
		URI uri = request.getURI();
		return request.getMethod() + " " + uri.getScheme() + "://" + uri.getRawAuthority() + uri.getRawPath();
	}

	public void close() {
		if (executorService != null) {
			executorService.shutdown();
			try {
				if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
					executorService.shutdownNow();
				}
			} catch (InterruptedException e) {
				executorService.shutdownNow();
				Thread.currentThread().interrupt();
			}
		}

		try {
			transportProvider.close();
		} catch (Exception e) {
			logger.error("Error closing HTTP transport provider", e);
		}

		if (config.isEnableLogging()) {
			logger.info("Closed API client for interface: {}", apiInterface.getName());
		}
	}
}