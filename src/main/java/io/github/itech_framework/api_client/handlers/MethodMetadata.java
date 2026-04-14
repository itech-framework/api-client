package io.github.itech_framework.api_client.handlers;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.itech_framework.api_client.annotations.methods.DELETE;
import io.github.itech_framework.api_client.annotations.methods.GET;
import io.github.itech_framework.api_client.annotations.methods.HEAD;
import io.github.itech_framework.api_client.annotations.methods.Header;
import io.github.itech_framework.api_client.annotations.methods.OPTIONS;
import io.github.itech_framework.api_client.annotations.methods.PATCH;
import io.github.itech_framework.api_client.annotations.methods.POST;
import io.github.itech_framework.api_client.annotations.methods.PUT;
import io.github.itech_framework.api_client.annotations.parameters.BaseUrl;
import io.github.itech_framework.api_client.annotations.parameters.Body;
import io.github.itech_framework.api_client.annotations.parameters.Headers;
import io.github.itech_framework.api_client.annotations.parameters.Path;
import io.github.itech_framework.api_client.annotations.parameters.Query;

public class MethodMetadata {
	private final Method method;
	private final String httpMethod;
	private final String path;
	private final String baseUrl;
	private final List<ParameterMetadata> parameters = new ArrayList<>();
	private final Set<String> pathParamNames = new HashSet<>();

	public MethodMetadata(Method method, String httpMethod, String path, String baseUrl) {
		this.method = method;
		this.httpMethod = httpMethod;
		this.path = path;
		this.baseUrl = baseUrl;
		extractPathParams();
		extractParameters();
	}

	public static MethodMetadata fromMethod(Method method, String defaultBaseUrl) {
		String httpMethod = null;
		String path = null;

		if (method.isAnnotationPresent(GET.class)) {
			httpMethod = "GET";
			path = method.getAnnotation(GET.class).value();
		} else if (method.isAnnotationPresent(POST.class)) {
			httpMethod = "POST";
			path = method.getAnnotation(POST.class).value();
		} else if (method.isAnnotationPresent(PUT.class)) {
			httpMethod = "PUT";
			path = method.getAnnotation(PUT.class).value();
		} else if (method.isAnnotationPresent(DELETE.class)) {
			httpMethod = "DELETE";
			path = method.getAnnotation(DELETE.class).value();
		} else if (method.isAnnotationPresent(PATCH.class)) {
			httpMethod = "PATCH";
			path = method.getAnnotation(PATCH.class).value();
		} else if (method.isAnnotationPresent(HEAD.class)) {
			httpMethod = "HEAD";
			path = method.getAnnotation(HEAD.class).value();
		} else if (method.isAnnotationPresent(OPTIONS.class)) {
			httpMethod = "OPTIONS";
			path = method.getAnnotation(OPTIONS.class).value();
		}

		if (httpMethod == null) {
			throw new IllegalArgumentException("No HTTP method annotation found on method: " + method.getName());
		}

		return new MethodMetadata(method, httpMethod, path, defaultBaseUrl);
	}

	private void extractPathParams() {
		Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
		Matcher matcher = pattern.matcher(path);
		while (matcher.find()) {
			pathParamNames.add(matcher.group(1));
		}
	}

	private void extractParameters() {
		Parameter[] params = method.getParameters();
		for (int i = 0; i < params.length; i++) {
			parameters.add(new ParameterMetadata(i, params[i]));
		}
	}

	public String resolvePath(Map<String, Object> params) {
		String resolved = path;
		for (Map.Entry<String, Object> entry : params.entrySet()) {
			if (pathParamNames.contains(entry.getKey())) {
				resolved = resolved.replace("{" + entry.getKey() + "}",
						entry.getValue() != null ? entry.getValue().toString() : "");
			}
		}
		return resolved;
	}

	public Map<String, Object> extractPathParams(Object[] args) {
		Map<String, Object> params = new HashMap<>();
		for (ParameterMetadata param : parameters) {
			if (param.isPathParam() && param.index < args.length) {
				params.put(param.getName(), args[param.index]);
			}
		}
		return params;
	}

	public Map<String, Object> extractQueryParams(Object[] args) {
		Map<String, Object> params = new HashMap<>();
		for (ParameterMetadata param : parameters) {
			if (param.isQueryParam() && param.index < args.length) {
				params.put(param.getName(), args[param.index]);
			}
		}
		return params;
	}

	public Map<String, String> extractHeaders(Object[] args) {
		Map<String, String> headers = new HashMap<>();
		for (ParameterMetadata param : parameters) {
			if (param.isHeaderParam() && param.index < args.length) {
				String value = args[param.index] != null ? args[param.index].toString() : null;
				if (value != null) {
					headers.put(param.getName(), value);
				}
			}
		}
		// Also extract from @Headers Map if present
		Map<String, String> mapHeaders = extractHeadersMap(args);
		if (mapHeaders != null) {
			headers.putAll(mapHeaders);
		}
		return headers;
	}

	private Map<String, String> extractHeadersMap(Object[] args) {
		for (ParameterMetadata param : parameters) {
			if (param.isHeadersMap() && param.index < args.length && args[param.index] != null) {
				Object mapObj = args[param.index];
				if (mapObj instanceof Map) {
					Map<?, ?> rawMap = (Map<?, ?>) mapObj;
					Map<String, String> stringMap = new HashMap<>();
					for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
						if (entry.getKey() != null) {
							stringMap.put(entry.getKey().toString(),
									entry.getValue() != null ? entry.getValue().toString() : null);
						}
					}
					return stringMap;
				}
			}
		}
		return null;
	}

	public Object extractBody(Object[] args) {
		for (ParameterMetadata param : parameters) {
			if (param.isBodyParam() && param.index < args.length) {
				return args[param.index];
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

	private static class ParameterMetadata {
		final int index;
		private final Parameter parameter;
		final ParamType type;
		final String name;

		ParameterMetadata(int index, Parameter parameter) {
			this.index = index;
			this.parameter = parameter;

			if (parameter.isAnnotationPresent(Path.class)) {
				type = ParamType.PATH;
				name = parameter.getAnnotation(Path.class).value();
			} else if (parameter.isAnnotationPresent(Query.class)) {
				type = ParamType.QUERY;
				name = parameter.getAnnotation(Query.class).value();
			} else if (parameter.isAnnotationPresent(Header.class)) {
				type = ParamType.HEADER;
				name = parameter.getAnnotation(Header.class).name();
			} else if (parameter.isAnnotationPresent(Body.class)) {
				type = ParamType.BODY;
				name = null;
			} else if (parameter.isAnnotationPresent(Headers.class)) {
				type = ParamType.HEADERS_MAP;
				name = null;
			} else if (parameter.isAnnotationPresent(BaseUrl.class)) {
				type = ParamType.BASE_URL;
				name = null;
			} else {
				type = ParamType.UNKNOWN;
				name = null;
			}
		}

		boolean isPathParam() {
			return type == ParamType.PATH;
		}

		boolean isQueryParam() {
			return type == ParamType.QUERY;
		}

		boolean isHeaderParam() {
			return type == ParamType.HEADER;
		}

		boolean isBodyParam() {
			return type == ParamType.BODY;
		}

		boolean isHeadersMap() {
			return type == ParamType.HEADERS_MAP;
		}

		/*
		 * boolean isBaseUrl() { return type == ParamType.BASE_URL; }
		 */

		String getName() {
			return name;
		}

		@SuppressWarnings("unused")
		public Parameter getParameter() {
			return parameter;
		}

		enum ParamType {
			PATH, QUERY, HEADER, BODY, HEADERS_MAP, BASE_URL, UNKNOWN
		}
	}
}