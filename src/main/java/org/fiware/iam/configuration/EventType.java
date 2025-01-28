package org.fiware.iam.configuration;

import io.micronaut.http.annotation.Get;
import lombok.Getter;

public enum EventType {
	CREATE("CreateEvent"),
	UPDATE("UpdateEvent"),
	DELETE("DeleteEvent"),
	STATE_CHANGE("StateChangeEvent");

	@Getter
	private final String value;

	EventType(String value) {
		this.value = value;
	}
}
