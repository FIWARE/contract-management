package org.fiware.iam.configuration;


import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties("notification")
public class NotificationProperties {

	private List<NotificationConfig> entities = new ArrayList<>();
}

