package io.github.itech_framework.api_client.exceptions;

import java.io.InterruptedIOException;

/**
 * Thrown while writing a body when the listener reports cancellation. It extends
 * InterruptedIOException on purpose: Apache HttpClient's own retry handler never retries it.
 */
public class RequestCancelledException extends InterruptedIOException {
	private static final long serialVersionUID = 1L;

	public RequestCancelledException() {
		super("Request cancelled");
	}
}