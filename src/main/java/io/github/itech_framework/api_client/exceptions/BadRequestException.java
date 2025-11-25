package io.github.itech_framework.api_client.exceptions;

public class BadRequestException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 5090165051940985171L;

	public BadRequestException(String message, String responseBody, String errorCode) {
        super(message, 400, responseBody, errorCode);
    }
}
