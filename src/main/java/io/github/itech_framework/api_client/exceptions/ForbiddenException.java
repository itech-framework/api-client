package io.github.itech_framework.api_client.exceptions;

public class ForbiddenException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 4759076553816314960L;

	public ForbiddenException(String message, String responseBody, String errorCode) {
        super(message, 403, responseBody, errorCode);
    }
}
