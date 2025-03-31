package org.itech.framework.fx.api_client.internal;

import org.itech.framework.fx.api_client.processor.ApiProcessor;
import org.itech.framework.fx.core.module.ComponentInitializer;

public class ApiClientInitializer implements ComponentInitializer {

    @Override
    public void initializeComponent(Class<?> aClass) {
        ApiProcessor.processApiClient(aClass);
    }
}
