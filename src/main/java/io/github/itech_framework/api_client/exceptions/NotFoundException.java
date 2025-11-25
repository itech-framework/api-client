package io.github.itech_framework.api_client.exceptions;

public class NotFoundException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 6903039560789357467L;

	public NotFoundException(String message, String responseBody, String errorCode) {
        super(message, 404, responseBody, errorCode);
    }
}