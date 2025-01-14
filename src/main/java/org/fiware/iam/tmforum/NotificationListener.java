package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.handlers.EventHandler;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Controller("${general.basepath:/}")
@RequiredArgsConstructor
public class NotificationListener {

	public static final String EVENT_TYPE_KEY = "eventType";

	private final List<EventHandler> eventHandlers;


	@Post("/listener/event")
	public Mono<HttpResponse<?>> listenToEvent(@Body Map<String, Object> event) {
		log.info("Received an Event: {}", event);
		if (!event.containsKey(EVENT_TYPE_KEY)) {
			throw new IllegalArgumentException("Data did not contain the eventType.");
		}

		if (event.get(EVENT_TYPE_KEY) instanceof String eventType) {
			return eventHandlers.stream()
					.filter(handler -> handler.isEventTypeSupported(eventType))
					.findAny()
					.orElseThrow(() -> new IllegalArgumentException("Event type %s is not supported.".formatted(eventType)))
					.handleEvent(eventType, event);
		}
		throw new IllegalArgumentException("Event type is invalid.");
	}
}