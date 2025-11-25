package io.github.itech_framework.api_client.exceptions;

public class TooManyRequestsException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 6787920584435193752L;

	public TooManyRequestsException(String message, String responseBody, String errorCode) {
        super(message, 429, responseBody, errorCode);
    }
}
