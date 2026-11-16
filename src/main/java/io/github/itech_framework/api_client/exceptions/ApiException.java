package io.github.itech_framework.api_client.exceptions;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ApiException extends RuntimeException {
	/**
	 * 
	 */
	private static final long serialVersionUID = 5644166100087948047L;

	private final int statusCode;
	private final String responseBody;
	private final String errorCode;

	private Map<String, List<String>> headers = Map.of();
	
	private byte[] responseBytes;
	private boolean bodyTruncated;

	public ApiException(String message, int statusCode, String responseBody, String errorCode) {
		super(message);
		this.statusCode = statusCode;
		this.responseBody = responseBody;
		this.errorCode = errorCode;
	}

	public ApiException(String message, int statusCode, String responseBody, String errorCode, Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.responseBody = responseBody;
		this.errorCode = errorCode;
	}

	public ApiException withHeaders(Map<String, List<String>> headers) {
		this.headers = headers == null ? Map.of() : headers;
		return this;
	}
	

	public ApiException withBytes(byte[] bytes, boolean truncated) {
	    this.responseBytes = bytes;
	    this.bodyTruncated = truncated;
	    return this;
	}

	// Getters
	public int getStatusCode() {
		return statusCode;
	}

	public String getResponseBody() {
		return responseBody;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	public Optional<String> header(String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	public byte[] getResponseBytes() {
	    return responseBytes == null ? null : responseBytes.clone();
	}

	public boolean isBodyTruncated() {
	    return bodyTruncated;
	}

	
}