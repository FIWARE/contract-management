package org.fiware.iam.tmforum.handlers;

import io.micronaut.http.HttpResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface EventHandler {


	boolean isEventTypeSupported(String eventType);

	Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event);
}
