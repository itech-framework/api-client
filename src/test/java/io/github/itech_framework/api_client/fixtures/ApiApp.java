package io.github.itech_framework.api_client.fixtures;

import io.github.itech_framework.core.annotations.ComponentScan;
import io.github.itech_framework.core.annotations.api_client.EnableApiClient;

@EnableApiClient
@ComponentScan(basePackage = "io.github.itech_framework.api_client.fixtures", properties = {})
public class ApiApp {
}