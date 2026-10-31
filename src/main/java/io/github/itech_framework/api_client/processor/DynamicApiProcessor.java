package io.github.itech_framework.api_client.processor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.handlers.DynamicApiClientInvocationHandler;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.utils.PlaceholderResolver;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.module.ComponentRegistry;
import io.github.itech_framework.core.processor.components_processor.ComponentProcessor;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;
import io.github.itech_framework.core.utils.AnnotationUtils;
import io.github.itech_framework.core.utils.PropertiesLoader;

public class DynamicApiProcessor {

	private static final Logger logger = LogManager.getLogger(DynamicApiProcessor.class);

	public static void processApiClient(Class<?> clazz, ComponentRegistry registry, CleanupRegistry cleanupRegistry) {
		if (!isValidApiClient(clazz)) {
			return;
		}

		try {
			ensureNotRegistered(clazz, registry);

			ApiClientConfig config = createConfiguration(clazz);

			Object proxy = createDynamicProxy(clazz, config);

			registry.registerComponent(clazz.getName(), proxy, ComponentProcessor.DEFAULT_LEVEL);

			registerCleanup(proxy, cleanupRegistry);

		} catch (Exception e) {
			throw new FrameworkException(
					"Failed to create API client proxy for: " + clazz.getName() + " - " + e.getMessage(), e);
		}
	}

	private static void ensureNotRegistered(Class<?> clazz, ComponentRegistry registry) {
		if (registry.hasComponent(clazz.getName())) {
			throw new FrameworkException("Duplicate API client registration: " + clazz.getName());
		}
	}

	private static void registerCleanup(Object proxy, CleanupRegistry cleanupRegistry) {
		Object handler = Proxy.getInvocationHandler(proxy);
		if (handler instanceof DynamicApiClientInvocationHandler apiHandler) {
			cleanupRegistry.register(apiHandler::close, CleanupRegistry.AFTER_BEANS);
		}
	}

	private static boolean isValidApiClient(Class<?> clazz) {
		return AnnotationUtils.hasAnnotation(clazz, ApiClient.class) && clazz.isInterface();
	}

	private static ApiClientConfig createConfiguration(Class<?> clazz) {
		ApiClient apiClient = findApiClient(clazz);

		ApiClientConfig.Builder builder = ApiClientConfig.builder().baseUrl(resolveBaseUrl(apiClient, clazz))
				.connectTimeout(getIntProperty(clazz, "connection.timeout", null, 30000))
				.socketTimeout(getIntProperty(clazz, "socket.timeout", null, 30000))
				.maxConnections(getIntProperty(clazz, "max.connections", "maxConnections", 10))
				.enableAsync(getBooleanProperty(clazz, "async.enabled", "enableAsync", true))
				.asyncThreadPoolSize(getIntProperty(clazz, "async.pool.size", "asyncThreadPoolSize", 10))
				.asyncQueueFullPolicy(getQueueFullPolicy(clazz))
				.sslVerification(getBooleanProperty(clazz, "ssl.verification", "sslVerification", true))
				.followRedirects(getBooleanProperty(clazz, "follow.redirects", "followRedirects", true))
				.enableLogging(getBooleanProperty(clazz, "logging.enabled", "enableLogging", true))
				.loggingLevel(getStringProperty(clazz, "logging.level", "loggingLevel", "INFO"))
				.maxRetries(getIntProperty(clazz, "retry.max", null, 2))
				.retryBackoffMillis(getLongProperty(clazz, "retry.backoff", null, 200L))
				.retryMaxBackoffMillis(getLongProperty(clazz, "retry.max.backoff", "retry.maxBackoff", 5000L));

		for (Class<? extends Interceptor> type : apiClient.interceptors()) {
			builder.addInterceptor(newInterceptor(type, clazz));
		}
		return builder.build();
	}

	private static ApiClient findApiClient(Class<?> clazz) {
		ApiClient direct = clazz.getAnnotation(ApiClient.class);
		if (direct != null) {
			return direct;
		}
		for (Annotation annotation : clazz.getAnnotations()) {
			ApiClient meta = annotation.annotationType().getAnnotation(ApiClient.class);
			if (meta != null) {
				return meta;
			}
		}
		return null;
	}

	private static String resolveBaseUrl(ApiClient apiClient, Class<?> clazz) {
		String baseUrl = PlaceholderResolver.resolve(apiClient.baseUrl());
		if (baseUrl.isBlank()) {
			baseUrl = lookup(clazz, "base.url", "baseUrl").value();
		}
		if (baseUrl == null || baseUrl.isBlank()) {
			baseUrl = PropertiesLoader.getProperty("flexi.api.baseUrl", "");
		}
		return baseUrl == null ? "" : baseUrl.trim();
	}

	private record PropertyValue(String key, String value) {
		boolean isSet() {
			return value != null && !value.isBlank();
		}
	}

	private static PropertyValue lookup(Class<?> clazz, String suffix, String legacySuffix) {
		String key = getPropertyKey(clazz, suffix);
		String value = PropertiesLoader.getProperty(key, "");
		if (value != null && !value.isBlank()) {
			return new PropertyValue(key, value);
		}
		if (legacySuffix != null && !legacySuffix.equals(suffix)) {
			String legacyKey = getPropertyKey(clazz, legacySuffix);
			String legacyValue = PropertiesLoader.getProperty(legacyKey, "");
			if (legacyValue != null && !legacyValue.isBlank()) {
				logger.warn("Property {} is deprecated, use {} instead", legacyKey, key);
				return new PropertyValue(legacyKey, legacyValue);
			}
		}
		return new PropertyValue(key, "");
	}

	private static String getStringProperty(Class<?> clazz, String suffix, String legacySuffix, String defaultValue) {
		PropertyValue property = lookup(clazz, suffix, legacySuffix);
		return property.isSet() ? property.value().trim() : defaultValue;
	}

	private static int getIntProperty(Class<?> clazz, String suffix, String legacySuffix, int defaultValue) {
		PropertyValue property = lookup(clazz, suffix, legacySuffix);
		if (!property.isSet()) {
			return defaultValue;
		}
		try {
			return Integer.parseInt(property.value().trim());
		} catch (NumberFormatException e) {
			throw invalidProperty(property.key(), property.value(), "a whole number", e);
		}
	}

	private static long getLongProperty(Class<?> clazz, String suffix, String legacySuffix, long defaultValue) {
		PropertyValue property = lookup(clazz, suffix, legacySuffix);
		if (!property.isSet()) {
			return defaultValue;
		}
		try {
			return Long.parseLong(property.value().trim());
		} catch (NumberFormatException e) {
			throw invalidProperty(property.key(), property.value(), "a whole number", e);
		}
	}

	private static boolean getBooleanProperty(Class<?> clazz, String suffix, String legacySuffix,
			boolean defaultValue) {
		PropertyValue property = lookup(clazz, suffix, legacySuffix);
		if (!property.isSet()) {
			return defaultValue;
		}
		String normalized = property.value().trim();
		if (normalized.equalsIgnoreCase("true")) {
			return true;
		}
		if (normalized.equalsIgnoreCase("false")) {
			return false;
		}
		throw invalidProperty(property.key(), property.value(), "true or false",
				new IllegalArgumentException(normalized));
	}

	private static FrameworkException invalidProperty(String key, String value, String expected, Exception cause) {
		return new FrameworkException("Invalid value '" + value + "' for property " + key + ": expected " + expected,
				cause);
	}

	private static String getPropertyKey(Class<?> clazz, String suffix) {
		return String.format("api.client.%s.%s", clazz.getSimpleName().toLowerCase(Locale.ROOT), suffix);
	}

	private static Object createDynamicProxy(Class<?> clazz, ApiClientConfig config) {
		return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz },
				new DynamicApiClientInvocationHandler(clazz, config));
	}

	private static ApiClientConfig.QueueFullPolicy getQueueFullPolicy(Class<?> clazz) {
		PropertyValue property = lookup(clazz, "async.queue.full", null);
		if (!property.isSet()) {
			return ApiClientConfig.QueueFullPolicy.REJECT;
		}
		String normalized = property.value().trim().toLowerCase(Locale.ROOT).replace('_', '-');
		return switch (normalized) {
		case "reject" -> ApiClientConfig.QueueFullPolicy.REJECT;
		case "caller-runs" -> ApiClientConfig.QueueFullPolicy.CALLER_RUNS;
		default -> throw invalidProperty(property.key(), property.value(), "reject or caller-runs",
				new IllegalArgumentException(normalized));
		};
	}

	private static Interceptor newInterceptor(Class<? extends Interceptor> type, Class<?> apiInterface) {
		try {
			Constructor<? extends Interceptor> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException e) {
			throw new FrameworkException("Interceptor " + type.getName() + " on " + apiInterface.getName()
					+ " needs a no-argument constructor", e);
		}
	}
}