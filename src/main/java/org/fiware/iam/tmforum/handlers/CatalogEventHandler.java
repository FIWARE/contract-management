package org.fiware.iam.tmforum.handlers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.RainbowMapper;
import org.fiware.iam.tmforum.productcatalog.model.*;
import org.fiware.rainbow.api.CatalogApiClient;
import org.fiware.rainbow.model.NewCatalogVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Singleton
@Slf4j
public class CatalogEventHandler implements EventHandler {

	private static final String CREATE_EVENT = "CatalogCreateEvent";
	private static final String DELETE_EVENT = "CatalogDeleteEvent";
	private static final String STATE_CHANGE_EVENT = "CatalogStateChangeEvent";

	private static final List<String> SUPPORTED_EVENT_TYPES = List.of(CREATE_EVENT, DELETE_EVENT, STATE_CHANGE_EVENT);

	private final CatalogApiClient catalogApiClient;
	private final ObjectMapper objectMapper;
	private final RainbowMapper rainbowMapper;

	@Override
	public boolean isEventTypeSupported(String eventType) {
		return SUPPORTED_EVENT_TYPES.contains(eventType);
	}

	@Override
	public Mono<HttpResponse<?>> handleEvent(String eventType, Map<String, Object> event) {
		return switch (eventType) {
			case CREATE_EVENT -> handleCatalogCreation(event);
			case STATE_CHANGE_EVENT -> handleCatalogStateChange(event);
			case DELETE_EVENT -> handleCatalogDeletion(event);
			default -> throw new IllegalArgumentException("Even type %s is not supported.".formatted(eventType));
		};
	}

	private Mono<HttpResponse<?>> handleCatalogCreation(Map<String, Object> createEvent) {
		CatalogCreateEventVO catalogCreateEventVO = objectMapper.convertValue(createEvent, CatalogCreateEventVO.class);
		CatalogVO catalogVO = Optional.ofNullable(catalogCreateEventVO.getEvent())
				.map(CatalogCreateEventPayloadVO::getCatalog)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a catalog."));
		NewCatalogVO rainbowCatalog = rainbowMapper.map(catalogVO);
		return catalogApiClient.createCatalog(rainbowCatalog)
				.onErrorMap(t -> new IllegalArgumentException("Was not able create the catalog %s".formatted(rainbowCatalog), t))
				.map(HttpResponse::ok);
	}


	private Mono<HttpResponse<?>> handleCatalogStateChange(Map<String, Object> createEvent) {
		CatalogStateChangeEventVO catalogStateChangeEventVO = objectMapper.convertValue(createEvent, CatalogStateChangeEventVO.class);
		CatalogVO catalogVO = Optional.ofNullable(catalogStateChangeEventVO.getEvent())
				.map(CatalogStateChangeEventPayloadVO::getCatalog)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a catalog."));

		return catalogApiClient.updateCatalogById(catalogVO.getId(), rainbowMapper.map(catalogVO)).map(res -> {
			if (res.getStatus().getCode() >= 200 && res.getStatus().getCode() < 300) {
				return HttpResponse.noContent();
			}
			return HttpResponse.status(HttpStatus.BAD_GATEWAY);
		});
	}

	private Mono<HttpResponse<?>> handleCatalogDeletion(Map<String, Object> deleteEvent) {
		CatalogDeleteEventVO catalogDeleteEventVO = objectMapper.convertValue(deleteEvent, CatalogDeleteEventVO.class);
		CatalogVO catalogVO = Optional.ofNullable(catalogDeleteEventVO.getEvent())
				.map(CatalogDeleteEventPayloadVO::getCatalog)
				.orElseThrow(() -> new IllegalArgumentException("The event does not contain a catalog."));
		return catalogApiClient.deleteCatalogById(catalogVO.getId()).map(res -> {
			if (res.getStatus().getCode() >= 200 && res.getStatus().getCode() < 300) {
				return HttpResponse.noContent();
			}
			return HttpResponse.status(HttpStatus.BAD_GATEWAY);
		});
	}
}
