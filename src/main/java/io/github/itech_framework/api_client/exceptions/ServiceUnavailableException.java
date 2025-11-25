package io.github.itech_framework.api_client.exceptions;

public class ServiceUnavailableException extends ApiException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 4984811802356233328L;

	public ServiceUnavailableException(String message, String responseBody, String errorCode) {
        super(message, 503, responseBody, errorCode);
    }
}
