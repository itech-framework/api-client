package io.github.itech_framework.api_client.utils.response;

import java.util.List;

public record ApiExchange<T>(ApiResponse<T> response, SentRequest request, Timing timing,
        List<RedirectHop> redirects, int attempts) {

    public ApiExchange {
        redirects = redirects == null ? List.of() : List.copyOf(redirects);
    }

    public int status() {
        return response.status();
    }

    public T body() {
        return response.body();
    }
}