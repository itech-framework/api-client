package io.github.itech_framework.api_client.exceptions;

public class UnauthorizedException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = -367310389175977878L;

	public UnauthorizedException(String message, String responseBody, String errorCode) {
        super(message, 401, responseBody, errorCode);
    }
}