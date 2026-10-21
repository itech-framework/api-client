package io.github.itech_framework.api_client.handlers;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.itech_framework.api_client.exceptions.ApiException;
import io.github.itech_framework.api_client.exceptions.BadRequestException;
import io.github.itech_framework.api_client.exceptions.ClientErrorException;
import io.github.itech_framework.api_client.exceptions.ConflictException;
import io.github.itech_framework.api_client.exceptions.ForbiddenException;
import io.github.itech_framework.api_client.exceptions.InternalServerErrorException;
import io.github.itech_framework.api_client.exceptions.NotFoundException;
import io.github.itech_framework.api_client.exceptions.ServerErrorException;
import io.github.itech_framework.api_client.exceptions.ServiceUnavailableException;
import io.github.itech_framework.api_client.exceptions.TooManyRequestsException;
import io.github.itech_framework.api_client.exceptions.UnauthorizedException;
import io.github.itech_framework.api_client.utils.JsonUtils;

public class ErrorDecoder {
    private static final Logger logger = LogManager.getLogger(ErrorDecoder.class);
    private static final String UNKNOWN = "UNKNOWN_ERROR";

    public ApiException decode(int statusCode, String responseBody) {
        String errorCode = extractErrorCode(responseBody);
        String message = String.format("API request failed with status %d: %s", statusCode,
                defaultMessage(statusCode));
        return switch (statusCode) {
        case 400 -> new BadRequestException(message, responseBody, errorCode);
        case 401 -> new UnauthorizedException(message, responseBody, errorCode);
        case 403 -> new ForbiddenException(message, responseBody, errorCode);
        case 404 -> new NotFoundException(message, responseBody, errorCode);
        case 409 -> new ConflictException(message, responseBody, errorCode);
        case 429 -> new TooManyRequestsException(message, responseBody, errorCode);
        case 500 -> new InternalServerErrorException(message, responseBody, errorCode);
        case 503 -> new ServiceUnavailableException(message, responseBody, errorCode);
        default -> {
            if (statusCode >= 500) {
                yield new ServerErrorException(message, statusCode, responseBody, errorCode);
            }
            if (statusCode >= 400) {
                yield new ClientErrorException(message, statusCode, responseBody, errorCode);
            }
            yield new ApiException(message, statusCode, responseBody, errorCode);
        }
        };
    }

    private String extractErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return UNKNOWN;
        }
        try {
            JsonNode node = JsonUtils.fromJson(body, JsonNode.class);
            if (node == null || !node.isObject()) {
                return UNKNOWN;
            }
            if (node.hasNonNull("code")) {
                return node.get("code").asText();
            }
            if (node.hasNonNull("errorCode")) {
                return node.get("errorCode").asText();
            }
            JsonNode error = node.get("error");
            if (error != null && !error.isNull()) {
                if (error.isValueNode()) {
                    return error.asText();
                }
                if (error.hasNonNull("code")) {
                    return error.get("code").asText();
                }
            }
        } catch (RuntimeException e) {
            logger.debug("Failed to extract error code from response", e);
        }
        return UNKNOWN;
    }

    private String defaultMessage(int statusCode) {
        return switch (statusCode) {
        case 400 -> "Bad Request";
        case 401 -> "Unauthorized";
        case 403 -> "Forbidden";
        case 404 -> "Not Found";
        case 409 -> "Conflict";
        case 429 -> "Too Many Requests";
        case 500 -> "Internal Server Error";
        case 503 -> "Service Unavailable";
        default -> "HTTP Error";
        };
    }
}