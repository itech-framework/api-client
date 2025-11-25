package io.github.itech_framework.api_client.exceptions;

public class ConflictException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = -7278879158226265158L;

	public ConflictException(String message, String responseBody, String errorCode) {
        super(message, 409, responseBody, errorCode);
    }
}
