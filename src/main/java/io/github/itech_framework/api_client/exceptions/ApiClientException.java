package io.github.itech_framework.api_client.exceptions;

import lombok.Getter;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;

import java.io.IOException;
import java.io.Serial;

@Getter
public class ApiClientException extends IOException {
    @Serial
    private static final long serialVersionUID = 1L;

    // Getters
    private final int statusCode;
    private final String responseBody;
    private final String reasonPhrase;

    // Full constructor
    public ApiClientException(String message,
                              int statusCode,
                              String responseBody,
                              String reasonPhrase,
                              Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.reasonPhrase = reasonPhrase;
    }

    // Constructor without cause
    public ApiClientException(String message,
                              int statusCode,
                              String responseBody,
                              String reasonPhrase) {
        this(message, statusCode, responseBody, reasonPhrase, null);
    }

    // Constructor without response body
    public ApiClientException(String message,
                              int statusCode,
                              String reasonPhrase) {
        this(message, statusCode, null, reasonPhrase, null);
    }

    // Minimal constructor
    public ApiClientException(String message,
                              int statusCode) {
        this(message, statusCode, null, null, null);
    }

    @Override
    public String toString() {
        return "ApiClientException{" +
                "message='" + getMessage() + '\'' +
                ", statusCode=" + statusCode +
                ", reasonPhrase='" + reasonPhrase + '\'' +
                ", responseBody='" + responseBody + '\'' +
                ", cause=" + getCause() +
                '}';
    }

    // Helper method to create from HttpResponse
    public static ApiClientException fromResponse(
            String message,
            HttpResponse response,
            String responseBody) {
        StatusLine statusLine = response.getStatusLine();
        return new ApiClientException(
                message,
                statusLine.getStatusCode(),
                responseBody,
                statusLine.getReasonPhrase()
        );
    }
}