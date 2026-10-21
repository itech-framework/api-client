package io.github.itech_framework.api_client.utils;

import java.io.IOException;
import java.lang.reflect.Type;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.github.itech_framework.core.exceptions.FrameworkException;

public class JsonUtils {
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtils() {
    }

    public static String toJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new FrameworkException("JSON serialization failed", e);
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new FrameworkException("JSON deserialization failed", e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new FrameworkException("JSON deserialization failed", e);
        }
    }

    public static <T> T fromJson(byte[] json, JavaType type) {
        if (json == null || json.length == 0) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new FrameworkException("JSON deserialization failed", e);
        }
    }

    public static JavaType constructType(Type type) {
        return mapper.getTypeFactory().constructType(type);
    }

    public static boolean isValidJson(String json) {
        try {
            mapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String toPrettyJson(Object object) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new FrameworkException("JSON pretty-print failed", e);
        }
    }
}