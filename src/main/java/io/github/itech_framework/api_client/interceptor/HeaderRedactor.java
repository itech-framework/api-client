package io.github.itech_framework.api_client.interceptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.http.Header;
import org.apache.http.HttpMessage;

public final class HeaderRedactor {

    public static final String MASK = "***";

    private static final Set<String> DEFAULT_NAMES = Set.of("authorization", "proxy-authorization", "cookie",
            "set-cookie", "x-api-key", "api-key", "x-auth-token");

    private static final HeaderRedactor DEFAULTS = new HeaderRedactor(DEFAULT_NAMES);

    private final Set<String> names;

    private HeaderRedactor(Set<String> names) {
        this.names = Set.copyOf(names);
    }

    public static HeaderRedactor defaults() {
        return DEFAULTS;
    }

    public HeaderRedactor with(String... extraNames) {
        Set<String> merged = new HashSet<>(names);
        if (extraNames != null) {
            for (String name : extraNames) {
                if (name != null && !name.isBlank()) {
                    merged.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new HeaderRedactor(merged);
    }

    public boolean isSensitive(String name) {
        return name != null && names.contains(name.toLowerCase(Locale.ROOT));
    }

    public String value(String name, String value) {
        if (value == null || !isSensitive(name)) {
            return value;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("authorization") || lower.equals("proxy-authorization")) {
            int space = value.indexOf(' ');
            return space > 0 ? value.substring(0, space) + " " + MASK : MASK;
        }
        return MASK;
    }

    public Map<String, List<String>> headers(HttpMessage message) {
        Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Header header : message.getAllHeaders()) {
            result.computeIfAbsent(header.getName(), key -> new ArrayList<>())
                    .add(value(header.getName(), header.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    public Map<String, List<String>> redact(Map<String, List<String>> headers) {
        Map<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                List<String> values = new ArrayList<>();
                if (entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        values.add(value(entry.getKey(), value));
                    }
                }
                result.put(entry.getKey(), values);
            }
        }
        return Collections.unmodifiableMap(result);
    }
}