package io.github.itech_framework.api_client.exceptions;

public class ServerErrorException extends ApiException {
	/**
	 * 
	 */
	private static final long serialVersionUID = 6490335509633646390L;

	public ServerErrorException(String message, int statusCode, String responseBody, String errorCode) {
		super(message, statusCode, responseBody, errorCode);
	}
}
