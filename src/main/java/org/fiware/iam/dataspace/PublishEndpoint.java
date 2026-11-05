package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.management.endpoint.annotation.Endpoint;
import io.micronaut.management.endpoint.annotation.Write;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;

import java.util.Optional;

/**
 * Internal {@code POST :9090/events}: Allows to send events to external subscribers.
 * Unauthenticated, so it relies on the management port being cluster-internal (see {@link ManagementPortGuard}).
 */
@Slf4j
@RequiredArgsConstructor
@Endpoint(id = PublishEndpoint.ID, defaultSensitive = false)
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class PublishEndpoint {

    static final String ID = "events";

    private final OwnEventPublisher ownEventPublisher;

    @Write
    public HttpResponse<PublishedEvent> publish(@Nullable String type, @Nullable String subject, @Nullable Object data,
                                                @Nullable String id) {
        if (type == null || type.isBlank()) {
            return HttpResponse.badRequest();
        }
        Optional<PublishedEvent> published = ownEventPublisher.publish(type, subject, plain(data), id);
        // receivers reject an event without source, after the 202 the caller would never learn about the drop
        if (published.isEmpty()) {
            log.error("Cannot publish event of type {}: neither dataspace.events.own-participant-id nor general.did is set.", type);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        }
        PublishedEvent event = published.get();
        log.info("Published event {} of type {} to {} subscriptions.", event.id(), type, event.subscriptions());
        return HttpResponse.accepted().body(event);
    }

    // endpoint arguments bind untyped values as JSON tree nodes; deliveries serialize plain maps, lists and scalars
    @Nullable
    private static Object plain(@Nullable Object data) {
        return data instanceof JsonNode node ? node.getValue() : data;
    }

    /**
     * Answer to a publish: the event id and how many subscriptions it was queued for.
     */
    @Introspected
    public record PublishedEvent(String id, int subscriptions) {
    }
}
