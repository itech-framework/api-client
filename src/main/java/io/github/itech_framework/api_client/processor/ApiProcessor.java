package io.github.itech_framework.api_client.processor;

import io.github.itech_framework.api_client.annotations.ApiClient;
import io.github.itech_framework.api_client.handlers.ApiClientInvocationHandler;
import io.github.itech_framework.core.processor.components_processor.ComponentProcessor;
import io.github.itech_framework.core.store.ComponentStore;
import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.utils.AnnotationUtils;

import java.lang.reflect.Proxy;

public class ApiProcessor {
    private static final String API_CLIENT_ERROR = "API client must be an interface";

    public static void processApiClient(Class<?> clazz) {
        System.out.println("Processing API class: " + clazz.getName());
        System.out.println("Is valid: " + isValidApiClient(clazz));
        if (isValidApiClient(clazz)) {
            validateIsInterface(clazz);
            registerApiClientProxy(clazz);
        }
    }

    private static boolean isValidApiClient(Class<?> clazz) {
        return AnnotationUtils.hasAnnotation(clazz, ApiClient.class) && clazz.isInterface();
    }

    private static void validateIsInterface(Class<?> clazz) {
        if (!clazz.isInterface() && !AnnotationUtils.hasAnnotation(clazz, ApiClient.class)) {
            throw new FrameworkException(API_CLIENT_ERROR + ": " + clazz.getName());
        }
    }

    private static void registerApiClientProxy(Class<?> clazz) {
        try {
            Object proxy = createApiClientProxy(clazz);
            registerInComponentStore(clazz, proxy);
        } catch (Exception e) {
            throw new FrameworkException("Failed to create API client proxy for: " + clazz.getName(), e);
        }
    }

    private static Object createApiClientProxy(Class<?> clazz) {
        return Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class[]{clazz},
                new ApiClientInvocationHandler(clazz)
        );
    }

    private static void registerInComponentStore(Class<?> clazz, Object proxy) {
        String key = clazz.getName();
        if (ComponentStore.components.containsKey(key)) {
            throw new FrameworkException("Duplicate API client registration: " + key);
        }
        ComponentStore.registerComponent(key, proxy, ComponentProcessor.DEFAULT_LEVEL);
    }
}