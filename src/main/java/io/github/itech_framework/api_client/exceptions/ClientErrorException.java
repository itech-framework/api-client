package io.github.itech_framework.api_client.exceptions;

public class ClientErrorException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 870791169066928208L;

	public ClientErrorException(String message, int statusCode, String responseBody, String errorCode) {
        super(message, statusCode, responseBody, errorCode);
    }
}
