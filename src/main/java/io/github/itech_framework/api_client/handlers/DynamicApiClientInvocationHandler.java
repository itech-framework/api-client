package io.github.itech_framework.api_client.handlers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.ParseException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
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

import com.fasterxml.jackson.databind.JavaType;

import io.github.itech_framework.api_client.auth.AuthProviderFactory;
import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;
import io.github.itech_framework.api_client.transport.impl.DefaultHttpTransportProvider;
import io.github.itech_framework.api_client.utils.JsonUtils;
import io.github.itech_framework.api_client.utils.body.BodyEncoders;
import io.github.itech_framework.api_client.utils.response.ApiResponse;
import io.github.itech_framework.api_client.utils.response.StreamResponse;
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
		this.executorService = config.isEnableAsync()
				? createExecutor(apiInterface, config.getAsyncThreadPoolSize(), config.getAsyncQueueFullPolicy())
				: null;

		initializeMethodHandlers();

		if (config.isEnableLogging()) {
			logger.info("Initialized API client for interface: {} with config: {}", apiInterface.getName(), config);
		}
	}

	private static ExecutorService createExecutor(Class<?> apiInterface, int requestedSize,
			ApiClientConfig.QueueFullPolicy queueFullPolicy) {
		int size = Math.max(1, requestedSize);
		AtomicInteger counter = new AtomicInteger();
		RejectedExecutionHandler rejectionHandler = queueFullPolicy == ApiClientConfig.QueueFullPolicy.CALLER_RUNS
				? new ThreadPoolExecutor.CallerRunsPolicy()
				: new ThreadPoolExecutor.AbortPolicy();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size, 60L, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(size * 100), runnable -> {
					Thread thread = new Thread(runnable,
							"api-client-" + apiInterface.getSimpleName() + "-" + counter.incrementAndGet());
					thread.setDaemon(true);
					return thread;
				}, rejectionHandler);
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
		
		private record Captured(byte[] bytes, boolean truncated) {
		}

		private Captured capture(CloseableHttpResponse response, boolean truncate) throws IOException {
		    HttpEntity entity = response.getEntity();
		    long limit = config.getMaxBodyBytes();
		    if (limit <= 0) {
		        return new Captured(EntityUtils.toByteArray(entity), false);
		    }
		    if (!truncate && entity.getContentLength() > limit) {
		        throw tooLarge(response, limit);
		    }
		    InputStream in = entity.getContent();
		    if (in == null) {
		        return new Captured(new byte[0], false);
		    }
		    try (in) {
		        ByteArrayOutputStream out = new ByteArrayOutputStream();
		        byte[] buffer = new byte[8192];
		        long total = 0;
		        int read;
		        while ((read = in.read(buffer)) != -1) {
		            long room = limit - total;
		            if (read > room) {
		                if (!truncate) {
		                    throw tooLarge(response, limit);
		                }
		                out.write(buffer, 0, (int) room);
		                return new Captured(out.toByteArray(), true);
		            }
		            out.write(buffer, 0, read);
		            total += read;
		        }
		        return new Captured(out.toByteArray(), false);
		    }
		}

		private ApiException tooLarge(CloseableHttpResponse response, long limit) {
		    return new ApiException("Response body is larger than " + limit + " bytes",
		            response.getStatusLine().getStatusCode(), null, "BODY_TOO_LARGE")
		            .withHeaders(headersOf(response));
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
			if (executorService.isShutdown()) {
				return CompletableFuture.failedFuture(
						new IllegalStateException("API client " + apiInterface.getSimpleName() + " is closed"));
			}
			try {
				return CompletableFuture.supplyAsync(() -> {
					try {
						return invokeSync(args);
					} catch (Exception e) {
						throw new CompletionException(e);
					}
				}, executorService);
			} catch (RejectedExecutionException e) {
				if (executorService.isShutdown()) {
					return CompletableFuture.failedFuture(
							new IllegalStateException("API client " + apiInterface.getSimpleName() + " is closed", e));
				}
				return CompletableFuture
						.failedFuture(new ApiException(
								"Too many pending async requests for " + apiInterface.getSimpleName() + "."
										+ method.getName() + ": the queue is full (see property async.queue.full)",
								0, null, "QUEUE_FULL", e));
			}
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
		    String callerUrl = metadata.extractBaseUrl(args);
		    String effectiveBaseUrl = callerUrl;
		    if (effectiveBaseUrl == null || effectiveBaseUrl.isBlank()) {
		        effectiveBaseUrl = metadata.getFullUrl();
		    }
		    if (effectiveBaseUrl == null || effectiveBaseUrl.isBlank()) {
		        throw new FrameworkException("Base url is not defined for " + apiInterface.getName()
		                + ". Use @ApiClient(baseUrl = \"...\"), the property api.client."
		                + apiInterface.getSimpleName().toLowerCase(Locale.ROOT)
		                + ".base.url, the property flexi.api.baseUrl, or a @BaseUrl String parameter.");
		    }

		    URI uri = exactUri(callerUrl, args);
		    if (uri == null) {
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
		        uri = uriBuilder.build();
		    }

		    BodyResolver.Resolved resolved = bodyResolver.resolve(args);
		    HttpUriRequest request = createHttpRequest(uri, resolved != null);
		    for (NameValuePair header : metadata.extractHeaders(args)) {
		        request.addHeader(header.getName(), header.getValue());
		    }
		    applyBody(request, resolved);
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

		private URI exactUri(String callerUrl, Object[] args) {
		    if (callerUrl == null || callerUrl.isBlank()) {
		        return null;
		    }
		    if (!metadata.resolvePathSegments(args).isEmpty() || !metadata.extractQueryParams(args).isEmpty()) {
		        return null;
		    }
		    try {
		        URI uri = new URI(callerUrl.trim());
		        return uri.getScheme() != null && uri.getHost() != null ? uri : null;
		    } catch (URISyntaxException e) {
		        return null;
		    }
		}

		private HttpContext createContext() {
			HttpContext context = new BasicHttpContext();
			context.setAttribute("method", method);
			context.setAttribute("config", config);
			context.setAttribute("timestamp", System.currentTimeMillis());
			return context;
		}

		private void notifyResponse(HttpResponse response, HttpContext context) {
			for (Interceptor interceptor : config.getInterceptors()) {
				try {
					interceptor.onResponse(response, context);
				} catch (RuntimeException failure) {
					logger.warn("Interceptor {} failed in onResponse", interceptor.getClass().getName(), failure);
				}
			}
		}

		private void notifyError(Exception error, HttpContext context) {
			for (Interceptor interceptor : config.getInterceptors()) {
				try {
					interceptor.onError(error, context);
				} catch (RuntimeException failure) {
					logger.warn("Interceptor {} failed in onError", interceptor.getClass().getName(), failure);
					if (failure != error) {
						error.addSuppressed(failure);
					}
				}
			}
		}
		
		private Object executeRequest(PreparedRequest prepared, HttpContext context) throws Exception {
			for (Interceptor interceptor : config.getInterceptors()) {
				interceptor.onRequest(prepared.request(), context);
			}
			CloseableHttpResponse response = null;
			boolean keepOpen = false;
			try {
				response = transportProvider.execute(prepared.request(), prepared.auth(), context);
				notifyResponse(response, context);
				
				Object result = processResponse(response);
				keepOpen = result instanceof StreamResponse;
				return result;
			} catch (Exception e) {
				notifyError(e, context);
				throw e;
			} finally {
				if (response != null && !keepOpen) {
					try {
						response.close();
					} catch (IOException ignored) {
						// nothing useful to do
					}
				}
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
		 * Content type precedence: @Body(contentType) > Content-Type header
		 * from @Header/@Headers > default of the resolved body type. The entity's own
		 * Content-Type always replaces the header, so multipart requests get a valid
		 * generated boundary.
		 */
		private void applyBody(HttpUriRequest request, BodyResolver.Resolved resolved) throws IOException {
		    if (resolved == null || !(request instanceof HttpEntityEnclosingRequest enclosing)) {
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

		private HttpUriRequest createHttpRequest(URI uri, boolean hasBody) {
		    String method = metadata.getHttpMethod();
		    if (hasBody && (method.equals("GET") || method.equals("DELETE") || method.equals("OPTIONS"))) {
		        return new EntityRequest(method, uri);
		    }
		    return switch (method) {
		    case "GET" -> new HttpGet(uri);
		    case "POST" -> new HttpPost(uri);
		    case "PUT" -> new HttpPut(uri);
		    case "DELETE" -> new HttpDelete(uri);
		    case "PATCH" -> new HttpPatch(uri);
		    case "HEAD" -> new HttpHead(uri);
		    case "OPTIONS" -> new HttpOptions(uri);
		    default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
		    };
		}

		private Object processResponse(CloseableHttpResponse response) throws IOException {
		    int status = response.getStatusLine().getStatusCode();
		    HttpEntity entity = response.getEntity();
		    Class<?> resultClass = metadata.getResultClass();

		    if (status >= 400 && !returnsErrorStatus(resultClass)) {
		        Captured captured = entity == null ? null : capture(response, true);
		        String errorBody = captured == null ? null : new String(captured.bytes(), charsetOf(entity));
		        ApiException error = errorDecoder.decode(status, errorBody, headersOf(response));
		        throw error.withBytes(captured == null ? null : captured.bytes(),
		                captured != null && captured.truncated());
		    }

		    if (resultClass == StreamResponse.class) {
		        return new StreamResponse(response);
		    }
		    if (resultClass == ApiResponse.class) {
		        JavaType inner = metadata.getResultJavaType().containedTypeOrUnknown(0);
		        Object body = readBody(response, entity, status, inner.getRawClass(), inner);
		        return new ApiResponse<>(status, headersOf(response), body);
		    }
		    return readBody(response, entity, status, resultClass, metadata.getResultJavaType());
		}

		private Object readBody(CloseableHttpResponse response, HttpEntity entity, int status, Class<?> resultClass,
		        JavaType javaType) throws IOException {
		    if (resultClass == void.class || resultClass == Void.class) {
		        EntityUtils.consumeQuietly(entity);
		        return null;
		    }
		    if (entity == null) {
		        return null;
		    }
		    byte[] bytes = capture(response, false).bytes();
		    if (resultClass == String.class) {
		        return new String(bytes, charsetOf(entity));
		    }
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
		        return JsonUtils.fromJson(bytes, javaType);
		    } catch (FrameworkException e) {
		        String snippet = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8);
		        throw new ApiException("Failed to parse JSON response", status, snippet, "JSON_PARSE_ERROR", e);
		    }
		}

		private boolean returnsErrorStatus(Class<?> resultClass) {
		    if (config.isThrowOnError()) {
		        return false;
		    }
		    if (resultClass == StreamResponse.class) {
		        return true;
		    }
		    if (resultClass != ApiResponse.class) {
		        return false;
		    }
		    Class<?> inner = metadata.getResultJavaType().containedTypeOrUnknown(0).getRawClass();
		    return inner == String.class || inner == byte[].class || inner == InputStream.class;
		}

	}

	private static final class EntityRequest extends HttpEntityEnclosingRequestBase {
	    private final String method;

	    EntityRequest(String method, URI uri) {
	        this.method = method;
	        setURI(uri);
	    }

	    @Override
	    public String getMethod() {
	        return method;
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

	private static Map<String, List<String>> headersOf(HttpResponse response) {
		Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for (Header header : response.getAllHeaders()) {
			headers.computeIfAbsent(header.getName(), key -> new ArrayList<>()).add(header.getValue());
		}
		return Collections.unmodifiableMap(headers);
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