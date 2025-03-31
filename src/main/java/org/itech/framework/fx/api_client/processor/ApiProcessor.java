package org.itech.framework.fx.api_client.processor;

import org.itech.framework.fx.api_client.annotations.ApiClient;
import org.itech.framework.fx.api_client.handlers.ApiClientInvocationHandler;
import org.itech.framework.fx.core.processor.components_processor.ComponentProcessor;
import org.itech.framework.fx.core.store.ComponentStore;
import org.itech.framework.fx.exceptions.FrameworkException;
import java.lang.reflect.Proxy;

public class ApiProcessor {
    private static final String API_CLIENT_ERROR = "API client must be an interface";

    public static void processApiClient(Class<?> clazz) {
        if (isValidApiClient(clazz)) {
            validateIsInterface(clazz);
            registerApiClientProxy(clazz);
        }
    }

    private static boolean isValidApiClient(Class<?> clazz) {
        return clazz.isAnnotationPresent(ApiClient.class) && clazz.isInterface();
    }

    private static void validateIsInterface(Class<?> clazz) {
        if (!clazz.isInterface()) {
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