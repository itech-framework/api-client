package io.github.itech_framework.api_client.utils.response;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import io.github.itech_framework.api_client.interceptor.HeaderRedactor;

public record SentRequest(String method, String uri, Map<String, List<String>> headers) {

    public SentRequest {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((name, values) -> copy.put(name, values == null ? List.of() : List.copyOf(values)));
        }
        headers = Collections.unmodifiableMap(copy);
    }

    public Optional<String> header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }
    
    @Override
    public String toString() {
        return "SentRequest[method=" + method + ", uri=" + uri + ", headers="
                + HeaderRedactor.defaults().redact(headers) + "]";
    }
}