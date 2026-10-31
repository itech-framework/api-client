package io.github.itech_framework.api_client.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.itech_framework.core.exceptions.FrameworkException;
import io.github.itech_framework.core.utils.PropertiesLoader;

public final class PlaceholderResolver {
    private static final Pattern PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private PlaceholderResolver() {
    }

    public static String resolve(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        Matcher matcher = PATTERN.matcher(input);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = PropertiesLoader.getProperty(key, null);
            if (value == null) {
                throw new FrameworkException("Missing property '" + key + "' used as placeholder " + matcher.group());
            }
            matcher.appendReplacement(builder, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }
}