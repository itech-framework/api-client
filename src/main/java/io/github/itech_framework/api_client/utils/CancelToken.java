package io.github.itech_framework.api_client.utils;

import org.apache.http.client.methods.HttpUriRequest;

public final class CancelToken {

    private volatile boolean cancelled;
    private volatile HttpUriRequest request;

    public void cancel() {
        cancelled = true;
        HttpUriRequest current = request;
        if (current != null) {
            current.abort();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void attach(HttpUriRequest request) {
        this.request = request;
        if (cancelled) {
            request.abort();
        }
    }
}