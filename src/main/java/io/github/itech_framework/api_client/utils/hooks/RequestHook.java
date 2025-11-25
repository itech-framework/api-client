package io.github.itech_framework.api_client.utils.hooks;

import org.apache.http.client.methods.HttpUriRequest;

@FunctionalInterface
public interface RequestHook {
    void apply(HttpUriRequest request);
}
