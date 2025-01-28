package org.fiware.iam;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Data;

@Data
@ConfigurationProperties("test")
public class TestConfiguration {

	private boolean inContainer = false;

	private String brokerHost = "http://localhost:1026";
	private String tilHost = "http://localhost:8085";
	private String productOrderingManagementHost = "http://localhost:8081";
	private String partyCatalogHost = "http://localhost:8083";
	private String productCatalogHost = "http://localhost:8082";
	private String agreementHost = "http://localhost:8086";
	private String providerRainbowHost = "http://localhost:1234";
}
