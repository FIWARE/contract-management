package org.fiware.iam;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.env.Environment;
import lombok.Data;
import org.fiware.iam.configuration.GeneralProperties;

@Data
@ConfigurationProperties("test")
public class TestConfiguration {

	private static final String IN_CONTAINER_PROPERTY = "inContainer";

	private boolean inContainer = false;

	private String brokerHost = "http://localhost:1026";
	private String tilHost = "http://localhost:8085";
	private String productOrderingManagementHost = "http://localhost:8081";
	private String partyCatalogHost = "http://localhost:8083";
	private String productCatalogHost = "http://localhost:8082";
	private String agreementHost = "http://localhost:8086";
	private String quoteHost = "http://localhost:8087";
	private String providerRainbowHost = "http://localhost:1234";
	private String odrlPapHost = "http://localhost:8088";


	public static class InContainerCondition implements Condition {

		@Override
		public boolean matches(ConditionContext context) {
			// read system property, by-passing the context
			return Boolean.getBoolean(IN_CONTAINER_PROPERTY);
		}
	}

	public static class LocalCondition implements Condition {

		@Override
		public boolean matches(ConditionContext context) {
			// read system property, by-passing the context
			return !Boolean.getBoolean(IN_CONTAINER_PROPERTY);
		}
	}
}
