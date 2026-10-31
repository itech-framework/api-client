package io.github.itech_framework.api_client.internal;

import io.github.itech_framework.api_client.processor.DynamicApiProcessor;
import io.github.itech_framework.core.module.ComponentInitializer;
import io.github.itech_framework.core.module.ComponentRegistry;
import io.github.itech_framework.core.resourcecs.CleanupRegistry;

public class ApiClientInitializer implements ComponentInitializer {

    @Override
    public void initializeComponent(Class<?> aClass) {
        DynamicApiProcessor.processApiClient(aClass, new ComponentRegistry(), CleanupRegistry.current());
    }

    @Override
    public void initializeComponent(Class<?> aClass, ComponentRegistry registry, CleanupRegistry cleanupRegistry) {
        DynamicApiProcessor.processApiClient(aClass, registry, cleanupRegistry);
    }
}