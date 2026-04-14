package io.github.itech_framework.api_client.processor;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.config.ApiClientConfig;
import io.github.itech_framework.api_client.handlers.DynamicApiClientInvocationHandler;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.processor.components_processor.ComponentProcessor;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;
import io.github.itech_framework.core.store.ComponentStore;
import io.github.itech_framework.core.utils.AnnotationUtils;
import io.github.itech_framework.core.utils.PropertiesLoader;

public class DynamicApiProcessor {
    private static final Map<Class<?>, ApiClientConfig> configCache = new HashMap<>();
    
    public static void processApiClient(Class<?> clazz) {
        if (!isValidApiClient(clazz)) {
            return;
        }
        
        try {
            ApiClientConfig config = createConfiguration(clazz);
            
            Object proxy = createDynamicProxy(clazz, config);
            
            registerInComponentStore(clazz, proxy);
            
            registerCleanup(clazz);
            
        } catch (Exception e) {
            throw new FrameworkException("Failed to create API client proxy for: " + clazz.getName(), e);
        }
    }
    
    private static boolean isValidApiClient(Class<?> clazz) {
        return AnnotationUtils.hasAnnotation(clazz, ApiClient.class) && clazz.isInterface();
    }
    
    private static ApiClientConfig createConfiguration(Class<?> clazz) {
        // Check cache first
        if (configCache.containsKey(clazz)) {
            return configCache.get(clazz);
        }
        
        // Get annotation
        ApiClient apiClient = clazz.getAnnotation(ApiClient.class);
        
        // Start building configuration
        ApiClientConfig.Builder builder = ApiClientConfig.builder();
        
        // Base URL
        String baseUrl = resolveBaseUrl(apiClient, clazz);
        builder.baseUrl(baseUrl);
        
        // Timeouts
        int connectTimeout = getProperty(clazz, "connection.timeout", 30000);
        int socketTimeout = getProperty(clazz, "socket.timeout", 30000);
        builder.connectTimeout(connectTimeout);
        builder.socketTimeout(socketTimeout);
        
        // Connection pool
        int maxConnections = getProperty(clazz, "maxConnections", 10);
        builder.maxConnections(maxConnections);
        
        // Async settings
        boolean enableAsync = getProperty(clazz, "enableAsync", true);
        int asyncThreadPoolSize = getProperty(clazz, "asyncThreadPoolSize", 10);
        builder.enableAsync(enableAsync);
        builder.asyncThreadPoolSize(asyncThreadPoolSize);
        
        // SSL
        boolean sslVerification = getProperty(clazz, "sslVerification", true);
        builder.sslVerification(sslVerification);
        
        // Redirects
        boolean followRedirects = getProperty(clazz, "followRedirects", true);
        builder.followRedirects(followRedirects);
        
        // Logging
        boolean enableLogging = getProperty(clazz, "enableLogging", true);
        String loggingLevel = PropertiesLoader.getProperty(
            getPropertyKey(clazz, "loggingLevel"), "INFO");
        builder.enableLogging(enableLogging);
        builder.loggingLevel(loggingLevel);
        
        // Build config
        ApiClientConfig config = builder.build();
        
        // Cache it
        configCache.put(clazz, config);
        
        return config;
    }
    
    private static String resolveBaseUrl(ApiClient apiClient, Class<?> clazz) {
        String baseUrl = apiClient.baseUrl();
        
        baseUrl = resolvePlaceholders(baseUrl);
        
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            baseUrl = PropertiesLoader.getProperty(getPropertyKey(clazz, "baseUrl"), "");
        }
        
        return baseUrl;
    }
    
    private static String resolvePlaceholders(String input) {
        if (input == null) {
            return "";
        }
        
        while (input.contains("${") && input.contains("}")) {
            int start = input.indexOf("${");
            int end = input.indexOf("}", start);
            if (start < end) {
                String placeholder = input.substring(start + 2, end);
                String value = PropertiesLoader.getProperty(placeholder, "");
                input = input.substring(0, start) + value + input.substring(end + 1);
            }
        }
        
        return input;
    }
    
    private static int getProperty(Class<?> clazz, String suffix, int defaultValue) {
        String value = PropertiesLoader.getProperty(getPropertyKey(clazz, suffix), String.valueOf(defaultValue));
        return value != null ? Integer.parseInt(value) : defaultValue;
    }
    
    private static boolean getProperty(Class<?> clazz, String suffix, boolean defaultValue) {
        String value = PropertiesLoader.getProperty(getPropertyKey(clazz, suffix), String.valueOf(defaultValue));
        return value != null ? Boolean.parseBoolean(value) : defaultValue;
    }
    
    private static String getPropertyKey(Class<?> clazz, String suffix) {
        return String.format("api.client.%s.%s", 
            clazz.getSimpleName().toLowerCase(), 
            suffix);
    }
    
    private static Object createDynamicProxy(Class<?> clazz, ApiClientConfig config) {
        return Proxy.newProxyInstance(
            clazz.getClassLoader(),
            new Class[]{clazz},
            new DynamicApiClientInvocationHandler(clazz, config)
        );
    }
    
    private static void registerInComponentStore(Class<?> clazz, Object proxy) {
        String key = clazz.getName();
        if (ComponentStore.components.containsKey(key)) {
            throw new FrameworkException("Duplicate API client registration: " + key);
        }
        ComponentStore.registerComponent(key, proxy, ComponentProcessor.DEFAULT_LEVEL);
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