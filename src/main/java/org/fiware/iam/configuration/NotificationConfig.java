package org.fiware.iam.configuration;

import lombok.Data;

import java.util.List;

@Data
public class NotificationConfig {

	private String entityType;
	private String apiAddress;
	private List<EventType> eventTypes;
}
