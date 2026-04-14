package io.github.itech_framework.api_client.handlers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
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
import org.apache.http.entity.StringEntity;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;
import io.github.itech_framework.api_client.transport.impl.DefaultHttpTransportProvider;
import io.github.itech_framework.api_client.utils.JsonUtils;

public class DynamicApiClientInvocationHandler implements InvocationHandler {
	private static final Logger logger = LogManager.getLogger(DynamicApiClientInvocationHandler.class);

	private final Class<?> apiInterface;
	private final ApiClientConfig config;
	private final HttpTransportProvider transportProvider;
	private final ExecutorService executorService;
	private final Map<Method, MethodHandler> methodHandlers = new ConcurrentHashMap<>();

	public DynamicApiClientInvocationHandler(Class<?> apiInterface, ApiClientConfig config) {
		this.apiInterface = apiInterface;
		this.config = config;
		this.transportProvider = config.getHttpTransportProvider() != null ? config.getHttpTransportProvider()
				: new DefaultHttpTransportProvider(config);

		if (config.isEnableAsync()) {
			this.executorService = Executors.newFixedThreadPool(config.getAsyncThreadPoolSize());
		} else {
			this.executorService = null;
		}

		initializeMethodHandlers();

		if (config.isEnableLogging()) {
			logger.info("Initialized API client for interface: {} with config: {}", apiInterface.getName(), config);
		}
	}

	private void initializeMethodHandlers() {
		for (Method method : apiInterface.getDeclaredMethods()) {
			MethodHandler handler = new MethodHandler(method);
			methodHandlers.put(method, handler);

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

		MethodHandler handler = methodHandlers.get(method);
		if (handler == null) {
			handler = new MethodHandler(method);
			methodHandlers.put(method, handler);
		}

		return handler.invoke(args);
	}

	private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
		switch (method.getName()) {
		case "toString":
			return String.format("DynamicApiClientProxy[%s@%s]", apiInterface.getName(),
					Integer.toHexString(hashCode()));
		case "hashCode":
			return System.identityHashCode(proxy);
		case "equals":
			return proxy == args[0];
		default:
			throw new UnsupportedOperationException("Unsupported Object method: " + method.getName());
		}
	}

	private class MethodHandler {
		private final Method method;
		private final MethodMetadata metadata;

		public MethodHandler(Method method) {
			this.method = method;
			this.metadata = MethodMetadata.fromMethod(method, config.getBaseUrl());
		}

		public Object invoke(Object[] args) throws Exception {
			if (method.getReturnType() == CompletableFuture.class && config.isEnableAsync()) {
				return invokeAsync(args);
			} else {
				return invokeSync(args);
			}
		}

		private Object invokeSync(Object[] args) throws Exception {
			HttpUriRequest request = buildRequest(args);
			HttpContext context = createContext();

			if (config.isEnableLogging()) {
				logger.debug("Executing {} {} synchronously", request.getMethod(), request.getURI());
			}

			return executeRequest(request, context);
		}

		private CompletableFuture<Object> invokeAsync(Object[] args) {
			if (executorService == null) {
				throw new IllegalStateException("Async execution is disabled in config");
			}

			return CompletableFuture.supplyAsync(() -> {
				try {
					HttpUriRequest request = buildRequest(args);
					HttpContext context = createContext();

					if (config.isEnableLogging()) {
						logger.debug("Executing {} {} asynchronously", request.getMethod(), request.getURI());
					}

					return executeRequest(request, context);
				} catch (Exception e) {
					throw new CompletionException(e);
				}
			}, executorService);
		}

		private HttpUriRequest buildRequest(Object[] args) throws Exception {
			URIBuilder uriBuilder = new URIBuilder(metadata.getFullUrl());

			Map<String, Object> pathParams = metadata.extractPathParams(args);
			String methodPath = metadata.resolvePath(pathParams);

			String basePath = uriBuilder.getPath();
			if (basePath == null)
				basePath = "";
			String combinedPath = combinePaths(basePath, methodPath);
			uriBuilder.setPath(combinedPath);

			Map<String, Object> queryParams = metadata.extractQueryParams(args);
			for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
				if (entry.getValue() != null) {
					uriBuilder.addParameter(entry.getKey(), entry.getValue().toString());
				}
			}

			HttpUriRequest request = createHttpRequest(uriBuilder.build());

			applyHeaders(request, args);
			applyBody(request, args);
			applyAuthentication(request);
			applyDefaultHeaders(request);

			return request;
		}

		private String combinePaths(String basePath, String methodPath) {
			if (methodPath == null || methodPath.isEmpty()) {
				return basePath;
			}
			String normalizedBase = basePath;
			if (normalizedBase.endsWith("/")) {
				normalizedBase = normalizedBase.substring(0, normalizedBase.length() - 1);
			}
			String normalizedMethod = methodPath;
			if (!normalizedMethod.startsWith("/")) {
				normalizedMethod = "/" + normalizedMethod;
			}
			if (normalizedBase.isEmpty()) {
				return normalizedMethod;
			}
			return normalizedBase + normalizedMethod;
		}

		private HttpContext createContext() {
			HttpContext context = new BasicHttpContext();
			context.setAttribute("method", method);
			context.setAttribute("config", config);
			context.setAttribute("timestamp", System.currentTimeMillis());
			return context;
		}

		private Object executeRequest(HttpUriRequest request, HttpContext context) throws Exception {
			for (Interceptor interceptor : config.getInterceptors()) {
				interceptor.onRequest(request, context);
			}

			try (CloseableHttpResponse response = transportProvider.execute(request)) {
				for (Interceptor interceptor : config.getInterceptors()) {
					interceptor.onResponse(response, context);
				}

				return processResponse(response, context);
			} catch (Exception e) {
				for (Interceptor interceptor : config.getInterceptors()) {
					interceptor.onError(e, context);
				}
				throw e;
			}
		}

		private void applyAuthentication(HttpRequest request) {
			if (config.getAuthenticationProvider() != null) {
				HttpContext context = new BasicHttpContext();
				config.getAuthenticationProvider().apply(request, context);
			}
		}

		private void applyDefaultHeaders(HttpRequest request) {
			for (Map.Entry<String, String> entry : config.getDefaultHeaders().entrySet()) {
				if (!request.containsHeader(entry.getKey())) {
					request.addHeader(entry.getKey(), entry.getValue());
				}
			}
		}

		private void applyHeaders(HttpRequest request, Object[] args) {
			Map<String, String> headers = metadata.extractHeaders(args);
			for (Map.Entry<String, String> entry : headers.entrySet()) {
				request.addHeader(entry.getKey(), entry.getValue());
			}
		}

		private void applyBody(HttpRequest request, Object[] args) throws Exception {
			if (request instanceof HttpEntityEnclosingRequest) {
				Object body = metadata.extractBody(args);
				if (body != null) {
					String json = JsonUtils.toJson(body);
					StringEntity entity = new StringEntity(json, "UTF-8");
					entity.setContentType("application/json");
					((HttpEntityEnclosingRequest) request).setEntity(entity);
				}
			}
		}

		private HttpUriRequest createHttpRequest(URI uri) {
			return switch (metadata.getHttpMethod().toUpperCase()) {
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

		private Object processResponse(CloseableHttpResponse response, HttpContext context) throws Exception {
			int statusCode = response.getStatusLine().getStatusCode();

			String responseBody = null;
			if (response.getEntity() != null) {
				responseBody = org.apache.http.util.EntityUtils.toString(response.getEntity(), "UTF-8");
				org.apache.http.util.EntityUtils.consume(response.getEntity());
			}

			if (statusCode >= 400) {
				throw new RuntimeException(String.format("HTTP Error %d: %s", statusCode, responseBody));
			}

			if (method.getReturnType() == void.class) {
				return null;
			}

			if (method.getReturnType() == String.class) {
				return responseBody;
			}

			Type targetType = method.getGenericReturnType();
			if (method.getReturnType() == CompletableFuture.class && targetType instanceof ParameterizedType) {
				ParameterizedType paramType = (ParameterizedType) targetType;
				if (paramType.getRawType() == CompletableFuture.class) {
					Type actualType = paramType.getActualTypeArguments()[0];
					if (actualType == String.class) {
						return responseBody;
					}
					if (responseBody != null && !responseBody.trim().isEmpty()) {
						return JsonUtils.fromJson(responseBody, new TypeReference<>() {
							@Override
							public Type getType() {
								return actualType;
							}
						});
					}
					return null;
				}
			}

			if (responseBody != null && !responseBody.trim().isEmpty()) {
				return JsonUtils.fromJson(responseBody, new TypeReference<>() {
					@Override
					public Type getType() {
						return method.getGenericReturnType();
					}
				});
			}

			return null;
		}
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