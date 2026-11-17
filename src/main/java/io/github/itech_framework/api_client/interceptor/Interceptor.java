package io.github.itech_framework.api_client.interceptor;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;

public interface Interceptor {
    String METHOD = "method";
    String CONFIG = "config";
    String TIMESTAMP = "timestamp";
    
    String ATTEMPT = "attempt";

    String SENT_REQUEST = "sentRequest";
    
    String REDIRECTS = "redirects";

    String OPTIONS = "requestOptions";
    
    default void onBeforeSend(HttpRequest request, HttpContext context) {
    }

    default void onRequest(HttpRequest request, HttpContext context) {
    }

    default void onResponse(HttpResponse response, HttpContext context) {
    }

    default void onError(Exception exception, HttpContext context) {
    }
}