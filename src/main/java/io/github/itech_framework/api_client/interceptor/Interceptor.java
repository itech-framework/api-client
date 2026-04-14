package io.github.itech_framework.api_client.interceptor;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HttpContext;

public interface Interceptor {
	void onRequest(HttpRequest request, HttpContext context);
    void onResponse(HttpResponse response, HttpContext context);
    void onError(Exception exception, HttpContext context);
}
