package org.fiware.iam.tmforum;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.netty.DefaultHttpClient;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import io.micronaut.scheduling.TaskScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.NotificationProperties;
import org.fiware.iam.tmforum.party.model.EventSubscriptionInputVO;
import org.fiware.iam.tmforum.party.model.EventSubscriptionVO;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;


@Context
@RequiredArgsConstructor
@Slf4j
public class NotificationSubscriber {

	private static final List<String> EVENT_TYPES = List.of("CreateEvent");//, "DeleteEvent", "StateChangeEvent");
	private static final String QUERY_TEMPLATE = "eventType=%s%s";
	private static final String LISTENER_ADDRESS_TEMPLATE = "%s/hub";
	private static final String LISTENER_PATH = "/listener/event";
	private final NotificationProperties notificationProperties;

	private final DefaultHttpClient httpClient;

	private final TaskScheduler taskScheduler;

	@Value("${general.basepath:}")
	private String controllerPath;

	@Value("${micronaut.server.port:8080}")
	private String servicePort;

	@Value("${general.name:contract-management}")
	private String serviceUrl;

	@EventListener
	public void onApplicationEvent(ServerStartupEvent e) {

		notificationProperties.getEntities()
				.forEach(tmForumEntity ->
						Optional.ofNullable(tmForumEntity.getEventTypes())
								.orElse(List.of())
								.forEach(eventType ->
										createSubscription(tmForumEntity.getEntityType(), eventType.getValue(), tmForumEntity.getApiAddress())
								)
				);

	}

	public void createSubscription(String entityType, String evenType, String apiAddress) {
		String callbackUrl = String.format("http://%s:%s%s%s", serviceUrl, servicePort, controllerPath, LISTENER_PATH);
		log.info("Attempting to register subscription with callback {}", callbackUrl);

		EventSubscriptionInputVO subscription = new EventSubscriptionInputVO()
				.callback(callbackUrl)
				.query(String.format(QUERY_TEMPLATE, entityType, evenType));

		HttpRequest<?> request = HttpRequest.create(HttpMethod.POST, String.format(LISTENER_ADDRESS_TEMPLATE, apiAddress)).body(subscription);

		Mono.from(httpClient.exchange(request, EventSubscriptionVO.class))
				.onErrorResume(t -> {
					if (t instanceof HttpClientResponseException e) {
						log.error("Event registration failed for {} at {} - Cause: {} : {}", entityType, request.getUri(), e.getStatus(), e.getMessage());
						return Mono.empty();
					}
					log.error("Could not create subscription for {} in TM Forum API", entityType, t);
					return Mono.empty();
				}).subscribe();

	}
}
