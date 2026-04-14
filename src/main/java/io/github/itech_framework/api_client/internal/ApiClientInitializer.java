package io.github.itech_framework.api_client.internal;

import io.github.itech_framework.api_client.processor.DynamicApiProcessor;
import io.github.itech_framework.core.module.ComponentInitializer;

public class ApiClientInitializer implements ComponentInitializer {

    @Override
    public void initializeComponent(Class<?> aClass) {
        DynamicApiProcessor.processApiClient(aClass);
    }
}
