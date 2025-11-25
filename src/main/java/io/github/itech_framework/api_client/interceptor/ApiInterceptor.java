package io.github.itech_framework.api_client.interceptor;

import io.github.itech_framework.api_client.utils.hooks.RequestHook;

public interface ApiInterceptor {
    default void beforeRequest(RequestHook callback) {
        // default: no-op
    }

    default void afterResponse(RequestHook callback) {
        // default: no-op
    }
}
