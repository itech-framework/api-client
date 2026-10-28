package io.github.itech_framework.api_client.handlers;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;

import com.fasterxml.jackson.databind.JavaType;

import io.github.itech_framework.api_client.annotations.methods.DELETE;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.HEAD;
import io.github.itech_framework.api_client.annotations.methods.Header;
import io.github.itech_framework.api_client.annotations.methods.OPTIONS;
import io.github.itech_framework.api_client.annotations.methods.PATCH;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.methods.PUT;
import io.github.itech_framework.api_client.annotations.parameters.Auth;
import io.github.itech_framework.api_client.annotations.parameters.BaseUrl;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.annotations.parameters.Headers;
import io.github.itech_framework.api_client.annotations.parameters.Path;
import io.github.itech_framework.api_client.annotations.parameters.Query;
import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.utils.JsonUtils;

public class MethodMetadata {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final Method method;
    private final String httpMethod;
    private final String path;
    private final String pathTemplate;
    private final String baseUrl;
    private final List<ParameterMetadata> parameters = new ArrayList<>();
    private final List<String> templateSegments;
    private final List<NameValuePair> staticQuery;
    private final boolean trailingSlash;
    private final List<NameValuePair> staticHeaders;
    private final boolean async;
    private final Class<?> resultClass;
    private final JavaType resultJavaType;

    public MethodMetadata(Method method, String httpMethod, String path, String baseUrl) {
        this.method = method;
        this.httpMethod = httpMethod;
        this.path = path == null ? "" : path;
        this.baseUrl = baseUrl;
        extractParameters();
        String[] parts = splitQuery(this.path);
        this.pathTemplate = parts[0];
        this.staticQuery = parseQuery(parts[1]);
        this.templateSegments = splitTemplate(this.pathTemplate);
        this.trailingSlash = this.pathTemplate.length() > 1 && this.pathTemplate.endsWith("/");
        validatePlaceholders();
        this.staticHeaders = extractStaticHeaders();
        this.async = method.getReturnType() == CompletableFuture.class;
        Type resultType = async ? unwrapFuture(method.getGenericReturnType()) : method.getGenericReturnType();
        this.resultClass = rawClass(resultType);
        this.resultJavaType = isRawResult(resultClass) ? null : JsonUtils.constructType(resultType);
    }

    public static MethodMetadata fromMethod(Method method, String defaultBaseUrl) {
        if (method.isAnnotationPresent(GET.class)) {
            return new MethodMetadata(method, "GET", method.getAnnotation(GET.class).value(), defaultBaseUrl);
        }
        if (method.isAnnotationPresent(POST.class)) {
            return new MethodMetadata(method, "POST", method.getAnnotation(POST.class).value(), defaultBaseUrl);
        }
        if (method.isAnnotationPresent(PUT.class)) {
            return new MethodMetadata(method, "PUT", method.getAnnotation(PUT.class).value(), defaultBaseUrl);
        }
        if (method.isAnnotationPresent(DELETE.class)) {
            return new MethodMetadata(method, "DELETE", method.getAnnotation(DELETE.class).value(), defaultBaseUrl);
        }
        if (method.isAnnotationPresent(PATCH.class)) {
            return new MethodMetadata(method, "PATCH", method.getAnnotation(PATCH.class).value(), defaultBaseUrl);
        }
        if (method.isAnnotationPresent(HEAD.class)) {
            return new MethodMetadata(method, "HEAD", method.getAnnotation(HEAD.class).value(), defaultBaseUrl);
        }
        if (method.isAnnotationPresent(OPTIONS.class)) {
            return new MethodMetadata(method, "OPTIONS", method.getAnnotation(OPTIONS.class).value(), defaultBaseUrl);
        }
        throw new IllegalArgumentException("No HTTP method annotation found on method: "
                + method.getDeclaringClass().getName() + "." + method.getName());
    }

    private void extractParameters() {
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            parameters.add(new ParameterMetadata(i, params[i]));
        }
    }

    private static String[] splitQuery(String template) {
        int hash = template.indexOf('#');
        String clean = hash < 0 ? template : template.substring(0, hash);
        int mark = clean.indexOf('?');
        if (mark < 0) {
            return new String[] { clean, "" };
        }
        return new String[] { clean.substring(0, mark), clean.substring(mark + 1) };
    }

    private static List<NameValuePair> parseQuery(String query) {
        if (query.isEmpty()) {
            return List.of();
        }
        return List.copyOf(URLEncodedUtils.parse(query, StandardCharsets.UTF_8));
    }

    private static List<String> splitTemplate(String template) {
        List<String> segments = new ArrayList<>();
        for (String segment : template.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment);
            }
        }
        return segments;
    }

    private void validatePlaceholders() {
        Set<String> declared = new HashSet<>();
        for (ParameterMetadata param : parameters) {
            if (param.type == ParamType.PATH) {
                declared.add(param.name);
            }
        }
        Matcher matcher = PLACEHOLDER.matcher(path);
        while (matcher.find()) {
            if (!declared.contains(matcher.group(1))) {
                throw new IllegalArgumentException("Path placeholder {" + matcher.group(1)
                        + "} has no matching @Path parameter in " + method.getDeclaringClass().getName() + "."
                        + method.getName());
            }
        }
    }

    private List<NameValuePair> extractStaticHeaders() {
        List<NameValuePair> headers = new ArrayList<>();
        for (Header header : method.getAnnotationsByType(Header.class)) {
            headers.add(new BasicNameValuePair(header.name(), header.value()));
        }
        return headers;
    }

    private static Type unwrapFuture(Type returnType) {
        if (returnType instanceof ParameterizedType parameterized) {
            return parameterized.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> raw) {
            return raw;
        }
        return Object.class;
    }

    private static boolean isRawResult(Class<?> clazz) {
        return clazz == void.class || clazz == Void.class || clazz == String.class || clazz == byte[].class
                || clazz == InputStream.class;
    }

    private Map<String, String> pathValues(Object[] args) {
        Map<String, String> values = new HashMap<>();
        for (ParameterMetadata param : parameters) {
            if (param.type == ParamType.PATH) {
                Object value = args[param.index];
                if (value == null) {
                    throw new IllegalArgumentException("Path parameter '" + param.name + "' must not be null in "
                            + method.getName());
                }
                values.put(param.name, value.toString());
            }
        }
        return values;
    }

    public List<String> resolvePathSegments(Object[] args) {
        Map<String, String> values = pathValues(args);
        List<String> resolved = new ArrayList<>(templateSegments.size() + 1);
        for (String segment : templateSegments) {
            resolved.add(values.isEmpty() ? segment : substitute(segment, values));
        }
        if (trailingSlash) {
            resolved.add("");
        }
        return resolved;
    }

    private static String substitute(String segment, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(segment);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(builder, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    public String extractBaseUrl(Object[] args) {
        for (ParameterMetadata param : parameters) {
            if (param.type == ParamType.BASE_URL && args[param.index] != null) {
                return args[param.index].toString();
            }
        }
        return null;
    }

    public List<NameValuePair> extractQueryParams(Object[] args) {
        List<NameValuePair> params = new ArrayList<>();
        if (!staticQuery.isEmpty()) {
            Map<String, String> values = pathValues(args);
            for (NameValuePair pair : staticQuery) {
                String name = values.isEmpty() ? pair.getName() : substitute(pair.getName(), values);
                String value = pair.getValue();
                if (value != null && !values.isEmpty()) {
                    value = substitute(value, values);
                }
                params.add(new BasicNameValuePair(name, value));
            }
        }
        for (ParameterMetadata param : parameters) {
            if (param.type == ParamType.QUERY) {
                addQueryValue(params, param.name, args[param.index]);
            }
        }
        return params;
    }

    private static void addQueryValue(List<NameValuePair> params, String name, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addQueryValue(params, name, item);
            }
        } else if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                addQueryValue(params, name, Array.get(value, i));
            }
        } else {
            params.add(new BasicNameValuePair(name, value.toString()));
        }
    }

    public List<NameValuePair> extractHeaders(Object[] args) {
        List<NameValuePair> headers = new ArrayList<>(staticHeaders);
        for (ParameterMetadata param : parameters) {
            if (param.type != ParamType.HEADERS_MAP) {
                continue;
            }
            Object arg = args[param.index];
            if (arg == null) {
                continue;
            }
            if (!(arg instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("@Headers parameter must be a Map<String, String> in "
                        + method.getName());
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    headers.add(new BasicNameValuePair(entry.getKey().toString(), entry.getValue().toString()));
                }
            }
        }
        return headers;
    }

    public Object extractBody(Object[] args) {
        for (ParameterMetadata param : parameters) {
            if (param.type == ParamType.BODY) {
                return args[param.index];
            }
        }
        return null;
    }

    public AuthenticationProvider extractAuth(Object[] args) {
        for (ParameterMetadata param : parameters) {
            if (param.type == ParamType.AUTH) {
                Object value = args[param.index];
                if (value == null) {
                    return null;
                }
                if (value instanceof AuthenticationProvider provider) {
                    return provider;
                }
                throw new IllegalArgumentException("Auth parameter must be instance of the AuthenticationProvider");
            }
        }
        return null;
    }

    public String getFullUrl() {
        return baseUrl;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public boolean isAsync() {
        return async;
    }

    public Class<?> getResultClass() {
        return resultClass;
    }

    public JavaType getResultJavaType() {
        return resultJavaType;
    }

    private enum ParamType {
        AUTH, PATH, QUERY, BODY, HEADERS_MAP, BASE_URL, UNKNOWN
    }

    private static final class ParameterMetadata {
        private final int index;
        private final ParamType type;
        private final String name;

        ParameterMetadata(int index, Parameter parameter) {
            this.index = index;
            if (parameter.isAnnotationPresent(Path.class)) {
                this.type = ParamType.PATH;
                this.name = parameter.getAnnotation(Path.class).value();
            } else if (parameter.isAnnotationPresent(Query.class)) {
                this.type = ParamType.QUERY;
                this.name = parameter.getAnnotation(Query.class).value();
            } else if (parameter.isAnnotationPresent(Body.class)) {
                this.type = ParamType.BODY;
                this.name = null;
            } else if (parameter.isAnnotationPresent(Headers.class)) {
                this.type = ParamType.HEADERS_MAP;
                this.name = null;
            } else if (parameter.isAnnotationPresent(BaseUrl.class)) {
                this.type = ParamType.BASE_URL;
                this.name = null;
            } else if (parameter.isAnnotationPresent(Auth.class)) {
                this.type = ParamType.AUTH;
                this.name = null;
            } else {
                this.type = ParamType.UNKNOWN;
                this.name = null;
            }
        }
    }
}