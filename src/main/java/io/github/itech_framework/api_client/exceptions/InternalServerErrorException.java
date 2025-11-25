package io.github.itech_framework.api_client.exceptions;

public class InternalServerErrorException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 5691980404914365515L;

	public InternalServerErrorException(String message, String responseBody, String errorCode) {
        super(message, 500, responseBody, errorCode);
    }
}