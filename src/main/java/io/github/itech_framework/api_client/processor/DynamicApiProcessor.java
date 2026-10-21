package io.github.itech_framework.api_client.processor;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.handlers.DynamicApiClientInvocationHandler;
import io.github.itech_framework.api_client.utils.PlaceholderResolver;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.processor.components_processor.ComponentProcessor;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;
import io.github.itech_framework.core.store.ComponentStore;
import io.github.itech_framework.core.utils.AnnotationUtils;
import io.github.itech_framework.core.utils.PropertiesLoader;

public class DynamicApiProcessor {
    private static final Map<Class<?>, ApiClientConfig> configCache = new ConcurrentHashMap<>();

    public static void processApiClient(Class<?> clazz) {
        if (!isValidApiClient(clazz)) {
            return;
        }

        try {
            ensureNotRegistered(clazz);

            ApiClientConfig config = configCache.computeIfAbsent(clazz, DynamicApiProcessor::createConfiguration);

            Object proxy = createDynamicProxy(clazz, config);

            ComponentStore.registerComponent(clazz.getName(), proxy, ComponentProcessor.DEFAULT_LEVEL);

            registerCleanup(clazz);

        } catch (Exception e) {
            throw new FrameworkException("Failed to create API client proxy for: " + clazz.getName(), e);
        }
    }

    private static boolean isValidApiClient(Class<?> clazz) {
        return AnnotationUtils.hasAnnotation(clazz, ApiClient.class) && clazz.isInterface();
    }

    private static void ensureNotRegistered(Class<?> clazz) {
        if (ComponentStore.components.containsKey(clazz.getName())) {
            throw new FrameworkException("Duplicate API client registration: " + clazz.getName());
        }
    }

    private static ApiClientConfig createConfiguration(Class<?> clazz) {
        ApiClient apiClient = clazz.getAnnotation(ApiClient.class);

        return ApiClientConfig.builder()
                .baseUrl(resolveBaseUrl(apiClient, clazz))
                .connectTimeout(getIntProperty(clazz, "connection.timeout", 30000))
                .socketTimeout(getIntProperty(clazz, "socket.timeout", 30000))
                .maxConnections(getIntProperty(clazz, "maxConnections", 10))
                .enableAsync(getBooleanProperty(clazz, "enableAsync", true))
                .asyncThreadPoolSize(getIntProperty(clazz, "asyncThreadPoolSize", 10))
                .sslVerification(getBooleanProperty(clazz, "sslVerification", true))
                .followRedirects(getBooleanProperty(clazz, "followRedirects", true))
                .enableLogging(getBooleanProperty(clazz, "enableLogging", true))
                .loggingLevel(PropertiesLoader.getProperty(getPropertyKey(clazz, "loggingLevel"), "INFO"))
                .maxRetries(getIntProperty(clazz, "retry.max", 2))
                .retryBackoffMillis(getLongProperty(clazz, "retry.backoff", 200L))
                .retryMaxBackoffMillis(getLongProperty(clazz, "retry.maxBackoff", 5000L))
                .build();
    }

    private static String resolveBaseUrl(ApiClient apiClient, Class<?> clazz) {
        String baseUrl = PlaceholderResolver.resolve(apiClient.baseUrl());
        if (baseUrl.trim().isEmpty()) {
            baseUrl = PropertiesLoader.getProperty(getPropertyKey(clazz, "baseUrl"), "");
        }
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = PropertiesLoader.getProperty("flexi.api.baseUrl", "");
        }
        return baseUrl == null ? "" : baseUrl.trim();
    }

    private static int getIntProperty(Class<?> clazz, String suffix, int defaultValue) {
        String value = PropertiesLoader.getProperty(getPropertyKey(clazz, suffix), String.valueOf(defaultValue));
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static long getLongProperty(Class<?> clazz, String suffix, long defaultValue) {
        String value = PropertiesLoader.getProperty(getPropertyKey(clazz, suffix), String.valueOf(defaultValue));
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
    }

    private static boolean getBooleanProperty(Class<?> clazz, String suffix, boolean defaultValue) {
        String value = PropertiesLoader.getProperty(getPropertyKey(clazz, suffix), String.valueOf(defaultValue));
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value.trim());
    }

    private static String getPropertyKey(Class<?> clazz, String suffix) {
        return String.format("api.client.%s.%s", clazz.getSimpleName().toLowerCase(), suffix);
    }

    private static Object createDynamicProxy(Class<?> clazz, ApiClientConfig config) {
        return Proxy.newProxyInstance(clazz.getClassLoader(), new Class[] { clazz },
                new DynamicApiClientInvocationHandler(clazz, config));
    }

    private static void registerCleanup(Class<?> clazz) {
        String key = clazz.getName();

        CleanupRegistry.register(() -> {
            Object component = ComponentStore.getComponent(key);
            if (component instanceof Proxy) {
                Object handler = Proxy.getInvocationHandler(component);
                if (handler instanceof DynamicApiClientInvocationHandler) {
                    ((DynamicApiClientInvocationHandler) handler).close();
                }
            }
        }, ComponentProcessor.DEFAULT_LEVEL);
    }
}