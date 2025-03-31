package org.itech.framework.fx.api_client.handlers;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.*;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.itech.framework.fx.api_client.annotations.*;
import org.itech.framework.fx.api_client.annotations.authentications.*;
import org.itech.framework.fx.api_client.annotations.methods.*;
import org.itech.framework.fx.api_client.annotations.methods.Header;
import org.itech.framework.fx.api_client.annotations.parameters.Body;
import org.itech.framework.fx.api_client.annotations.parameters.Path;
import org.itech.framework.fx.api_client.annotations.parameters.Query;
import org.itech.framework.fx.api_client.auth.TokenManager;
import org.itech.framework.fx.api_client.exceptions.ApiClientException;
import org.itech.framework.fx.api_client.utils.JsonUtils;
import org.itech.framework.fx.core.utils.PropertiesLoader;
import org.itech.framework.fx.exceptions.FrameworkException;

import java.io.IOException;
import java.lang.reflect.*;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiClientInvocationHandler implements InvocationHandler {
    private final Class<?> apiInterface;
    private final String baseUrl;
    private final CloseableHttpClient httpClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    public ApiClientInvocationHandler(Class<?> apiInterface) {
        this.apiInterface = apiInterface;
        this.baseUrl = resolveBaseUrl();
        this.httpClient = createHttpClient();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
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
                Type returnType = ((ParameterizedType) method.getGenericReturnType())
                        .getActualTypeArguments()[0];
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
        processRequestBody(method, args, request);
        addHeaders(method, request, args);
        applyAuthentication(method, request);

        return httpClient.execute(request);
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
            if (param.isAnnotationPresent(org.itech.framework.fx.api_client.annotations.parameters.Headers.class)) {
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
        if (auth == null) auth = apiInterface.getAnnotation(Authenticated.class);
        if (auth == null) return;

        switch (auth.value()) {
            case BASIC:
                BasicAuth basicAuth = method.getAnnotation(BasicAuth.class);
                if (basicAuth == null) basicAuth = apiInterface.getAnnotation(BasicAuth.class);
                applyBasicAuth(request, basicAuth);
                break;
            case API_KEY:
                ApiKey apiKey = method.getAnnotation(ApiKey.class);
                if (apiKey == null) apiKey = apiInterface.getAnnotation(ApiKey.class);
                applyApiKey(request, apiKey);
                break;
            case BEARER:
                BearerToken bearer = method.getAnnotation(BearerToken.class);
                if (bearer == null) bearer = apiInterface.getAnnotation(BearerToken.class);
                applyBearerToken(request, bearer);
                break;
            case OAUTH2:
                OAuth2 oauth = method.getAnnotation(OAuth2.class);
                if (oauth == null) oauth = apiInterface.getAnnotation(OAuth2.class);
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

    private <T> T processResponse(CloseableHttpResponse response, TypeReference<T> typeRef)
            throws IOException, ApiClientException {

        HttpEntity entity = response.getEntity();
        String responseBody = null;
        StatusLine statusLine = response.getStatusLine();
        int statusCode = statusLine.getStatusCode();

        try {
            if (entity != null) {
                ContentType contentType = ContentType.getOrDefault(entity);
                Charset charset = contentType.getCharset() != null
                        ? contentType.getCharset()
                        : StandardCharsets.UTF_8;
                responseBody = EntityUtils.toString(entity, charset);
            }

            if (statusCode < 200 || statusCode >= 300) {
                throw ApiClientException.fromResponse(
                        "API request failed",
                        response,
                        responseBody
                );
            }

            if (responseBody == null || responseBody.isEmpty()) {
                return null;
            }

            // Check Content-Type more flexibly
            ContentType responseType = ContentType.getOrDefault(entity);
            String receivedMimeType = responseType.getMimeType();
            String expectedMimeType = ContentType.APPLICATION_JSON.getMimeType();

            if (!receivedMimeType.equalsIgnoreCase(expectedMimeType)) {
                throw new ApiClientException(
                        "Unexpected content type: " + responseType,
                        statusCode,
                        responseBody,
                        "Invalid Content-Type"
                );
            }

            return JsonUtils.fromJson(responseBody, typeRef);

        } catch (JsonParseException e) {
            throw new ApiClientException(
                    "Failed to parse JSON response",
                    statusCode,
                    responseBody,
                    "JSON Parsing Error",
                    e
            );
        } finally {
            EntityUtils.consumeQuietly(entity);
        }
    }
    private CloseableHttpClient createHttpClient() {
        return HttpClients.custom()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(30 * 1000)
                        .setSocketTimeout(30 * 1000)
                        .setRedirectsEnabled(true)
                        .build())
                .addInterceptorFirst(new LoggingInterceptor())
                .build();
    }

    private String resolveBaseUrl() {
        ApiClient apiClient = apiInterface.getAnnotation(ApiClient.class);
        String baseUrl = apiClient.baseUrl();
        String resolvedUrl = baseUrl.isEmpty() ?
                PropertiesLoader.getProperty("flexi.api.baseUrl", "") :
                baseUrl;
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

    protected static class HttpMethodInfo {
        private final String method;
        private final String path;

        HttpMethodInfo(String method, String path) {
            this.method = method;
            this.path = path;
        }

        String getMethod() { return method; }
        String getPath() { return path; }
    }

    private static class LoggingInterceptor implements HttpRequestInterceptor {

        @Override
        public void process(HttpRequest request, HttpContext httpContext) throws HttpException, IOException {
            System.out.println("Request: " + request.getRequestLine().getMethod() + " " + request.getRequestLine().getUri());
        }
    }
}