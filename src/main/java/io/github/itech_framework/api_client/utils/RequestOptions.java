package io.github.itech_framework.api_client.utils;

import org.apache.http.HttpHost;

public final class RequestOptions {

    private final Integer connectTimeoutMillis;
    private final Integer socketTimeoutMillis;
    private final Integer connectionRequestTimeoutMillis;
    private final Boolean followRedirects;
    private final Integer maxRedirects;
    private final HttpHost proxy;

    private RequestOptions(Builder builder) {
        this.connectTimeoutMillis = builder.connectTimeoutMillis;
        this.socketTimeoutMillis = builder.socketTimeoutMillis;
        this.connectionRequestTimeoutMillis = builder.connectionRequestTimeoutMillis;
        this.followRedirects = builder.followRedirects;
        this.maxRedirects = builder.maxRedirects;
        this.proxy = builder.proxy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Integer getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public Integer getSocketTimeoutMillis() {
        return socketTimeoutMillis;
    }

    public Integer getConnectionRequestTimeoutMillis() {
        return connectionRequestTimeoutMillis;
    }

    public Boolean getFollowRedirects() {
        return followRedirects;
    }

    public Integer getMaxRedirects() {
        return maxRedirects;
    }

    public HttpHost getProxy() {
        return proxy;
    }

    public static final class Builder {
        private Integer connectTimeoutMillis;
        private Integer socketTimeoutMillis;
        private Integer connectionRequestTimeoutMillis;
        private Boolean followRedirects;
        private Integer maxRedirects;
        private HttpHost proxy;

        private Builder() {
        }

        public Builder connectTimeoutMillis(int millis) {
            this.connectTimeoutMillis = nonNegative(millis, "connectTimeoutMillis");
            return this;
        }

        public Builder socketTimeoutMillis(int millis) {
            this.socketTimeoutMillis = nonNegative(millis, "socketTimeoutMillis");
            return this;
        }

        public Builder connectionRequestTimeoutMillis(int millis) {
            this.connectionRequestTimeoutMillis = nonNegative(millis, "connectionRequestTimeoutMillis");
            return this;
        }

        public Builder timeoutMillis(int millis) {
            connectTimeoutMillis(millis);
            return socketTimeoutMillis(millis);
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public Builder maxRedirects(int maxRedirects) {
            this.maxRedirects = nonNegative(maxRedirects, "maxRedirects");
            return this;
        }

        public Builder proxy(String host, int port) {
            return proxy(host, port, "http");
        }

        public Builder proxy(String host, int port, String scheme) {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("proxy host must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("proxy port must be between 1 and 65535");
            }
            String proxyScheme = scheme == null || scheme.isBlank() ? "http" : scheme.trim();
            this.proxy = new HttpHost(host.trim(), port, proxyScheme);
            return this;
        }

        public RequestOptions build() {
            return new RequestOptions(this);
        }

        private static int nonNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}