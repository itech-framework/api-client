package io.github.itech_framework.api_client.utils.response;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Return type that exposes the status and headers next to the parsed body:
 * {@code ApiResponse<DriveFileResponse> create(...)}.
 * Header lookup is case-insensitive.
 */
public record ApiResponse<T>(int status, Map<String, List<String>> headers, T body) {

	public Optional<String> header(String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	public boolean isSuccessful() {
		return status >= 200 && status < 300;
	}
}