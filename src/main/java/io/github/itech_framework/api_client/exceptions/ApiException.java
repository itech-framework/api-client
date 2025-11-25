package io.github.itech_framework.api_client.exceptions;

public class ApiException extends RuntimeException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 5644166100087948047L;
	
	private final int statusCode;
    private final String responseBody;
    private final String errorCode;
    
    public ApiException(String message, int statusCode, String responseBody, String errorCode) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errorCode = errorCode;
    }
    
    public ApiException(String message, int statusCode, String responseBody, String errorCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.errorCode = errorCode;
    }
    
    // Getters
    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
    public String getErrorCode() { return errorCode; }
}