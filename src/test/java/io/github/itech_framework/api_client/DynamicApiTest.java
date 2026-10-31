package io.github.itech_framework.api_client;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.github.itech_framework.api_client.processor.DynamicApiProcessor;
import io.github.itech_framework.core.module.ComponentRegistry;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;
import io.github.itech_framework.core.store.ComponentStore;
import io.github.itech_framework.core.utils.PropertiesLoader;

abstract class DynamicApiTest extends BaseClientTest {

	private PropertiesLoader.Snapshot originalProperties;
	private ComponentStore store;
	private CleanupRegistry cleanup;

	@BeforeEach
	void prepare() {
		originalProperties = PropertiesLoader.snapshot();
		store = new ComponentStore();
		cleanup = new CleanupRegistry();
	}

	@AfterEach
	void restore() {
		cleanup.cleanup();
		PropertiesLoader.restore(originalProperties);
	}

	protected static void useProperties(String... keysAndValues) {
		Properties properties = new Properties();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			properties.setProperty(keysAndValues[i], keysAndValues[i + 1]);
		}
		PropertiesLoader.restore(new PropertiesLoader.Snapshot(Set.of(), Map.of(), properties, "test"));
	}

	protected <T> T processed(Class<T> api) {
		DynamicApiProcessor.processApiClient(api, new ComponentRegistry(store), cleanup);
		return api.cast(store.getComponent(api.getName()));
	}
}