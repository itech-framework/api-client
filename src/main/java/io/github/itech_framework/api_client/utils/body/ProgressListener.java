package io.github.itech_framework.api_client.utils.body;

public interface ProgressListener {

	/** @param total the body length, or -1 when unknown */
	void onProgress(long transferred, long total);

	default boolean isCancelled() {
		return false;
	}
}