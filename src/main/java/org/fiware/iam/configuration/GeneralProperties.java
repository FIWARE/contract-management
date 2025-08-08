package org.fiware.iam.configuration;


import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Data;

@Data
@ConfigurationProperties("general")
public class GeneralProperties {

	private String did;
}
