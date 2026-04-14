package io.github.itech_framework.api_client.auth;

import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpContext;

public interface AuthenticationProvider {
    void apply(HttpRequest request, HttpContext context);
    void refresh();
    boolean isExpired();
}