package io.github.itech_framework.api_client.handlers;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.annotations.authentications.ApiKey;
import io.github.itech_framework.api_client.annotations.authentications.Authenticated;
import io.github.itech_framework.api_client.annotations.authentications.BasicAuth;
import io.github.itech_framework.api_client.annotations.authentications.BearerToken;
import io.github.itech_framework.api_client.annotations.authentications.OAuth2;
import io.github.itech_framework.api_client.annotations.methods.DELETE;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.Header;
import io.github.itech_framework.api_client.annotations.methods.OPTION;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.methods.PUT;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.annotations.parameters.Headers;
import io.github.itech_framework.api_client.annotations.parameters.Path;
import io.github.itech_framework.api_client.annotations.parameters.Query;
import io.github.itech_framework.api_client.auth.TokenManager;
import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.exceptions.BadRequestException;
import io.github.itech_framework.api_client.exceptions.ClientErrorException;
import io.github.itech_framework.api_client.exceptions.ConflictException;
import io.github.itech_framework.api_client.exceptions.ForbiddenException;
import io.github.itech_framework.api_client.exceptions.InternalServerErrorException;
import io.github.itech_framework.api_client.exceptions.NotFoundException;
import io.github.itech_framework.api_client.exceptions.ServerErrorException;
import io.github.itech_framework.api_client.exceptions.ServiceUnavailableException;
import io.github.itech_framework.api_client.exceptions.TooManyRequestsException;
import io.github.itech_framework.api_client.exceptions.UnauthorizedException;
import io.github.itech_framework.api_client.interceptor.ApiInterceptor;
import io.github.itech_framework.api_client.utils.JsonUtils;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.utils.PropertiesLoader;

public class ApiClientInvocationHandler implements InvocationHandler {
	private final Class<?> apiInterface;
	private final String baseUrl;
	private final CloseableHttpClient httpClient;
	private final ExecutorService executor = Executors.newFixedThreadPool(10);

	private int connectionTimeout = 30 * 1000;
	private int socketTimeout = 30 * 1000;

	private final Logger logger = LogManager.getLogger(getClass());

	public ApiClientInvocationHandler(Class<?> apiInterface) {
		this.apiInterface = apiInterface;
		this.baseUrl = resolveBaseUrl();
		this.httpClient = createHttpClient();
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) {
			return handleObjectMethod(proxy, method, args);
		}

		if (method.getReturnType() == CompletableFuture.class) {
			return handleAsync(method, args);
		}
		return handleSync(method, args);
	}

	private Object handleSync(Method method, Object[] args) throws Exception {
		CloseableHttpResponse response = executeRequest(method, args);
		Type returnType = method.getGenericReturnType();
		return processResponse(response, new TypeReference<Object>() {
			@Override
			public Type getType() {
				return returnType;
			}
		});
	}

	private CompletableFuture<?> handleAsync(Method method, Object[] args) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				CloseableHttpResponse response = executeRequest(method, args);
				Type returnType = ((ParameterizedType) method.getGenericReturnType()).getActualTypeArguments()[0];
				return processResponse(response, new TypeReference<>() {
					@Override
					public Type getType() {
						return returnType;
					}
				});
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, executor);
	}

	private CloseableHttpResponse executeRequest(Method method, Object[] args) throws Exception {
		HttpMethodInfo httpMethodInfo = getHttpMethodInfo(method);
		String pathTemplate = httpMethodInfo.getPath();

		Map<String, String> pathParams = extractPathParams(method, args);
		String resolvedPath = replacePathParams(pathTemplate, pathParams);

		URIBuilder uriBuilder = new URIBuilder(baseUrl + resolvedPath);
		processQueryParams(method, args, uriBuilder);

		HttpUriRequest request = createRequest(httpMethodInfo, uriBuilder);

		// custom user define configuration
		if (ApiInterceptor.class.isAssignableFrom(apiInterface)) {

		}
		processRequestBody(method, args, request);
		addHeaders(method, request, args);
		applyAuthentication(method, request);

		return httpClient.execute(request);
	}

	private Object handleObjectMethod(Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
		case "toString" -> "ApiClientProxy[" + apiInterface.getName() + "]";
		case "hashCode" -> System.identityHashCode(proxy);
		case "equals" -> proxy == args[0];
		default -> throw new UnsupportedOperationException("Unsupported Object method: " + method.getName());
		};
	}

	private HttpMethodInfo getHttpMethodInfo(Method method) {
		if (method.isAnnotationPresent(GET.class)) {
			return new HttpMethodInfo("GET", method.getAnnotation(GET.class).value());
		} else if (method.isAnnotationPresent(POST.class)) {
			return new HttpMethodInfo("POST", method.getAnnotation(POST.class).value());
		} else if (method.isAnnotationPresent(PUT.class)) {
			return new HttpMethodInfo("PUT", method.getAnnotation(PUT.class).value());
		} else if (method.isAnnotationPresent(DELETE.class)) {
			return new HttpMethodInfo("DELETE", method.getAnnotation(DELETE.class).value());
		} else if (method.isAnnotationPresent(OPTION.class)) {
			return new HttpMethodInfo("OPTIONS", method.getAnnotation(OPTION.class).value());
		}
		throw new IllegalArgumentException("No HTTP method annotation found on method: " + method.getName());
	}

	private Map<String, String> extractPathParams(Method method, Object[] args) {
		Map<String, String> params = new HashMap<>();
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			Path path = parameters[i].getAnnotation(Path.class);
			if (path != null) {
				params.put(path.value(), args[i].toString());
			}
		}
		return params;
	}

	private String replacePathParams(String path, Map<String, String> params) {
		for (Map.Entry<String, String> entry : params.entrySet()) {
			path = path.replace("{" + entry.getKey() + "}", entry.getValue());
		}
		return path;
	}

	private void processQueryParams(Method method, Object[] args, URIBuilder uriBuilder) {
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			Query query = parameters[i].getAnnotation(Query.class);
			if (query != null) {
				uriBuilder.addParameter(query.value(), args[i].toString());
			}
		}
	}

	private HttpUriRequest createRequest(HttpMethodInfo methodInfo, URIBuilder uriBuilder) throws URISyntaxException {
		return switch (methodInfo.getMethod().toUpperCase()) {
		case "GET" -> new HttpGet(uriBuilder.build());
		case "POST" -> new HttpPost(uriBuilder.build());
		case "PUT" -> new HttpPut(uriBuilder.build());
		case "DELETE" -> new HttpDelete(uriBuilder.build());
		case "OPTIONS" -> new HttpOptions(uriBuilder.build());
		default -> throw new IllegalArgumentException("Unsupported HTTP method: " + methodInfo.getMethod());
		};
	}

	private void processRequestBody(Method method, Object[] args, HttpUriRequest request) {
		if (request instanceof HttpEntityEnclosingRequest) {
			for (int i = 0; i < method.getParameters().length; i++) {
				if (method.getParameters()[i].isAnnotationPresent(Body.class)) {
					Object bodyObj = args[i];
					String json = JsonUtils.toJson(bodyObj);
					StringEntity entity = new StringEntity(json, ContentType.APPLICATION_JSON);
					((HttpEntityEnclosingRequest) request).setEntity(entity);
					break;
				}
			}
		}
	}

	private void addHeaders(Method method, HttpUriRequest request, Object[] args) {
		processMethodHeaders(method, request);
		processParameterHeaders(method, request, args);
	}

	private void processMethodHeaders(Method method, HttpUriRequest request) {
		Header[] headers = method.getAnnotationsByType(Header.class);
		for (Header header : headers) {
			request.addHeader(header.name(), header.value());
		}
	}

	private void processParameterHeaders(Method method, HttpUriRequest request, Object[] args) {
		Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			Parameter param = parameters[i];
			if (param.isAnnotationPresent(Headers.class)) {
				Object arg = args[i];
				validateHeadersParameter(arg);
				addHeadersFromMap(request, (Map<?, ?>) arg);
			}
		}
	}

	private void validateHeadersParameter(Object arg) {
		if (!(arg instanceof Map)) {
			throw new FrameworkException("@Headers parameter must be a Map<String, String>");
		}
	}

	private void addHeadersFromMap(HttpUriRequest request, Map<?, ?> headersMap) {
		headersMap.forEach((key, value) -> {
			if (key != null && value != null) {
				request.addHeader(key.toString(), value.toString());
			}
		});
	}

	private void applyAuthentication(Method method, HttpUriRequest request) {
		Authenticated auth = method.getAnnotation(Authenticated.class);
		if (auth == null)
			auth = apiInterface.getAnnotation(Authenticated.class);
		if (auth == null)
			return;

		switch (auth.value()) {
		case BASIC:
			BasicAuth basicAuth = method.getAnnotation(BasicAuth.class);
			if (basicAuth == null)
				basicAuth = apiInterface.getAnnotation(BasicAuth.class);
			applyBasicAuth(request, basicAuth);
			break;
		case API_KEY:
			ApiKey apiKey = method.getAnnotation(ApiKey.class);
			if (apiKey == null)
				apiKey = apiInterface.getAnnotation(ApiKey.class);
			applyApiKey(request, apiKey);
			break;
		case BEARER:
			BearerToken bearer = method.getAnnotation(BearerToken.class);
			if (bearer == null)
				bearer = apiInterface.getAnnotation(BearerToken.class);
			applyBearerToken(request, bearer);
			break;
		case OAUTH2:
			OAuth2 oauth = method.getAnnotation(OAuth2.class);
			if (oauth == null)
				oauth = apiInterface.getAnnotation(OAuth2.class);
			applyOAuth2(request, oauth);
			break;
		}
	}

	private void applyBasicAuth(HttpUriRequest request, BasicAuth auth) {
		String credentials = auth.username() + ":" + auth.password();
		String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
		request.addHeader("Authorization", "Basic " + encoded);
	}

	private void applyApiKey(HttpUriRequest request, ApiKey apiKey) {
		if (apiKey.inHeader()) {
			request.addHeader(apiKey.name(), apiKey.value());
		} else {
			try {
				URIBuilder uriBuilder = new URIBuilder(request.getURI());
				uriBuilder.addParameter(apiKey.name(), apiKey.value());
				((HttpRequestBase) request).setURI(uriBuilder.build());
			} catch (URISyntaxException e) {
				throw new FrameworkException("Error adding API key to query parameters", e);
			}
		}
	}

	private void applyBearerToken(HttpUriRequest request, BearerToken bearer) {
		request.addHeader("Authorization", "Bearer " + bearer.token());
	}

	private void applyOAuth2(HttpUriRequest request, OAuth2 oauth) {
		String accessToken = TokenManager.getToken(oauth);
		request.addHeader("Authorization", "Bearer " + accessToken);
	}

	private <T> T processResponse(CloseableHttpResponse response, TypeReference<T> typeRef) throws IOException {

		HttpEntity entity = response.getEntity();
		String responseBody = null;
		StatusLine statusLine = response.getStatusLine();
		int statusCode = statusLine.getStatusCode();

		try {
			if (entity != null) {
				ContentType contentType = ContentType.getOrDefault(entity);
				Charset charset = contentType.getCharset() != null ? contentType.getCharset() : StandardCharsets.UTF_8;
				responseBody = EntityUtils.toString(entity, charset);
			}

			if (statusCode >= 400) {
				throw createExceptionForStatusCode(statusCode, responseBody, response);
			}

			if (responseBody == null || responseBody.trim().isEmpty()) {
				return null;
			}

			if (typeRef.getType() == String.class) {
				@SuppressWarnings("unchecked")
				T result = (T) responseBody;
				return result;
			}

			ContentType responseType = ContentType.getOrDefault(entity);
			String receivedMimeType = responseType.getMimeType();
			String expectedMimeType = ContentType.APPLICATION_JSON.getMimeType();

			if (!receivedMimeType.equalsIgnoreCase(expectedMimeType)) {
				throw new ApiException("Unexpected content type: " + responseType, statusCode, responseBody,
						"INVALID_CONTENT_TYPE");
			}

			return JsonUtils.fromJson(responseBody, typeRef);

		} catch (JsonParseException e) {
			throw new ApiException("Failed to parse JSON response", statusCode, responseBody, "JSON_PARSE_ERROR", e);
		} catch (ApiException e) {
			throw e;
		} catch (Exception e) {
			throw new ApiException("Unexpected error processing response: " + e.getMessage(), statusCode, responseBody,
					"PROCESSING_ERROR", e);
		} finally {
			EntityUtils.consumeQuietly(entity);
			if (response != null) {
				try {
					response.close();
				} catch (IOException e) {
					logger.warn("Error closing response", e);
				}
			}
		}
	}

	private ApiException createExceptionForStatusCode(int statusCode, String responseBody,
			CloseableHttpResponse response) {
		String errorCode = extractErrorCodeFromResponse(responseBody);
		String message = String.format("API request failed with status %d: %s", statusCode,
				getDefaultMessageForStatusCode(statusCode));

		switch (statusCode) {
		case 400:
			return new BadRequestException(message, responseBody, errorCode);
		case 401:
			return new UnauthorizedException(message, responseBody, errorCode);
		case 403:
			return new ForbiddenException(message, responseBody, errorCode);
		case 404:
			return new NotFoundException(message, responseBody, errorCode);
		case 409:
			return new ConflictException(message, responseBody, errorCode);
		case 429:
			return new TooManyRequestsException(message, responseBody, errorCode);
		case 500:
			return new InternalServerErrorException(message, responseBody, errorCode);
		case 503:
			return new ServiceUnavailableException(message, responseBody, errorCode);
		default:
			if (statusCode >= 400 && statusCode < 500) {
				return new ClientErrorException(message, statusCode, responseBody, errorCode);
			} else if (statusCode >= 500) {
				return new ServerErrorException(message, statusCode, responseBody, errorCode);
			} else {
				return new ApiException(message, statusCode, responseBody, errorCode);
			}
		}
	}

	private String extractErrorCodeFromResponse(String responseBody) {
		if (responseBody == null || responseBody.trim().isEmpty()) {
			return "UNKNOWN_ERROR";
		}

		try {
			JsonNode jsonNode = JsonUtils.fromJson(responseBody, JsonNode.class);
			if (jsonNode.has("code")) {
				return jsonNode.get("code").asText();
			} else if (jsonNode.has("errorCode")) {
				return jsonNode.get("errorCode").asText();
			} else if (jsonNode.has("error")) {
				JsonNode errorNode = jsonNode.get("error");
				if (errorNode.has("code")) {
					return errorNode.get("code").asText();
				}
				return errorNode.asText();
			}
		} catch (Exception e) {
			logger.debug("Failed to extract error code from response", e);
		}

		return "UNKNOWN_ERROR";
	}

	private String getDefaultMessageForStatusCode(int statusCode) {
		switch (statusCode) {
		case 400:
			return "Bad Request";
		case 401:
			return "Unauthorized";
		case 403:
			return "Forbidden";
		case 404:
			return "Not Found";
		case 409:
			return "Conflict";
		case 429:
			return "Too Many Requests";
		case 500:
			return "Internal Server Error";
		case 503:
			return "Service Unavailable";
		default:
			return "HTTP Error";
		}
	}

	@SuppressWarnings("deprecation")
	private CloseableHttpClient createHttpClient() {
		try {
			SSLContext sslContext = SSLContexts.custom().loadTrustMaterial(null, new TrustSelfSignedStrategy()).build();

			SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext,
					new String[] { "TLSv1.2", "TLSv1.3" }, // Specify protocols
					null, SSLConnectionSocketFactory.getDefaultHostnameVerifier());

			String connectionTimeOutStr = PropertiesLoader.getProperty("flexi.api.connection.timeout", "0");
			connectionTimeout = connectionTimeOutStr == null ? connectionTimeout
					: Integer.parseInt(connectionTimeOutStr);

			String socketTimeOutStr = PropertiesLoader.getProperty("flexi.api.socket.timeout", "0");
			socketTimeout = socketTimeOutStr == null ? socketTimeout : Integer.parseInt(socketTimeOutStr);

			return HttpClients.custom()
					.setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(connectionTimeout)
							.setSocketTimeout(socketTimeout).setRedirectsEnabled(true).build())
					.setSSLSocketFactory(sslSocketFactory).addInterceptorFirst(new LoggingInterceptor()).build();
		} catch (Exception e) {
			throw new FrameworkException("Failed to create HTTP client", e);
		}
	}

	private String resolveBaseUrl() {
		ApiClient apiClient = apiInterface.getAnnotation(ApiClient.class);
		String baseUrl = apiClient.baseUrl();
		String resolvedUrl = baseUrl.isEmpty() ? PropertiesLoader.getProperty("flexi.api.baseUrl", "") : baseUrl;
		return resolvePlaceholders(resolvedUrl);
	}

	private String resolvePlaceholders(String input) {
		Pattern pattern = Pattern.compile("\\$\\{(.+?)\\}");
		Matcher matcher = pattern.matcher(input);
		StringBuilder sb = new StringBuilder();

		while (matcher.find()) {
			String placeholder = matcher.group(1);
			String value = PropertiesLoader.getProperty(placeholder, "");
			matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(sb);

		return sb.toString();
	}

	// clean up
	public void close() {
		try {
			logger.debug("Shouting down executor...");
			executor.shutdown();
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
			}
			logger.debug("Executors shoutted down!");
		} catch (InterruptedException e) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}

		try {
			logger.debug("Closing http client...");
			httpClient.close();
			logger.debug("HTTP Client closed!");
		} catch (IOException e) {
			System.err.println("Error closing HttpClient: " + e.getMessage());
		}
	}

	protected static class HttpMethodInfo {
		private final String method;
		private final String path;

		HttpMethodInfo(String method, String path) {
			this.method = method;
			this.path = path;
		}

		String getMethod() {
			return method;
		}

		String getPath() {
			return path;
		}
	}

	private static class LoggingInterceptor implements HttpRequestInterceptor {

		@Override
		public void process(HttpRequest request, HttpContext httpContext) throws HttpException, IOException {
			System.out.println(
					"Request: " + request.getRequestLine().getMethod() + " " + request.getRequestLine().getUri());
		}
	}

}