package io.github.itech_framework.api_client.config;

import io.github.itech_framework.api_client.auth.AuthenticationProvider;
import io.github.itech_framework.api_client.interceptor.Interceptor;
import io.github.itech_framework.api_client.transport.HttpTransportProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ApiClientConfig {
    private String baseUrl;
    private int connectTimeout = 30000;
    private int socketTimeout = 30000;
    private int maxConnections = 10;
    private boolean followRedirects = true;
    private boolean sslVerification = true;
    private Map<String, String> defaultHeaders = new HashMap<>();
    private List<Interceptor> interceptors = new ArrayList<>();
    private AuthenticationProvider authenticationProvider;
    private HttpTransportProvider httpTransportProvider;
    private boolean enableAsync = true;
    private int asyncThreadPoolSize = 10;
    private boolean enableLogging = true;
    private String loggingLevel = "INFO";

    // Builder pattern for easy configuration
    public static class Builder {
        private final ApiClientConfig config = new ApiClientConfig();

        public Builder baseUrl(String baseUrl) {
            config.baseUrl = baseUrl;
            return this;
        }

        public Builder connectTimeout(int timeout) {
            config.connectTimeout = timeout;
            return this;
        }

        public Builder socketTimeout(int timeout) {
            config.socketTimeout = timeout;
            return this;
        }

        public Builder maxConnections(int max) {
            config.maxConnections = max;
            return this;
        }

        public Builder followRedirects(boolean follow) {
            config.followRedirects = follow;
            return this;
        }

        public Builder sslVerification(boolean verify) {
            config.sslVerification = verify;
            return this;
        }

        public Builder defaultHeader(String name, String value) {
            config.defaultHeaders.put(name, value);
            return this;
        }

        public Builder defaultHeaders(Map<String, String> headers) {
            config.defaultHeaders.putAll(headers);
            return this;
        }

        public Builder addInterceptor(Interceptor interceptor) {
            config.interceptors.add(interceptor);
            return this;
        }

        public Builder authenticationProvider(AuthenticationProvider provider) {
            config.authenticationProvider = provider;
            return this;
        }

        public Builder httpTransportProvider(HttpTransportProvider provider) {
            config.httpTransportProvider = provider;
            return this;
        }

        public Builder enableAsync(boolean enable) {
            config.enableAsync = enable;
            return this;
        }

        public Builder asyncThreadPoolSize(int size) {
            config.asyncThreadPoolSize = size;
            return this;
        }

        public Builder enableLogging(boolean enable) {
            config.enableLogging = enable;
            return this;
        }

        public Builder loggingLevel(String level) {
            config.loggingLevel = level;
            return this;
        }

        public ApiClientConfig build() {
            return config;
        }
    }

    // Getters
    public String getBaseUrl() { return baseUrl; }
    public int getConnectTimeout() { return connectTimeout; }
    public int getSocketTimeout() { return socketTimeout; }
    public int getMaxConnections() { return maxConnections; }
    public boolean isFollowRedirects() { return followRedirects; }
    public boolean isSslVerification() { return sslVerification; }
    public Map<String, String> getDefaultHeaders() { return defaultHeaders; }
    public List<Interceptor> getInterceptors() { return interceptors; }
    public AuthenticationProvider getAuthenticationProvider() { return authenticationProvider; }
    public HttpTransportProvider getHttpTransportProvider() { return httpTransportProvider; }
    public boolean isEnableAsync() { return enableAsync; }
    public int getAsyncThreadPoolSize() { return asyncThreadPoolSize; }
    public boolean isEnableLogging() { return enableLogging; }
    public String getLoggingLevel() { return loggingLevel; }

    public static Builder builder() {
        return new Builder();
    }
}