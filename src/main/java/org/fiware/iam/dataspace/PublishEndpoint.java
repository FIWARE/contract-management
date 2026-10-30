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
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.events.model.DataSpaceEventVO;

import java.time.Clock;
import java.util.UUID;

/**
 * Internal {@code POST :9090/events}: lets in-cluster callers publish an event to the matching subscriptions.
 * Unauthenticated, so it relies on the management port being cluster-internal (see {@link ManagementPortGuard}).
 */
@Slf4j
@RequiredArgsConstructor
@Endpoint(id = PublishEndpoint.ID, defaultSensitive = false)
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class PublishEndpoint {

    static final String ID = "events";
    static final String CONTENT_TYPE = "application/json";

    private final EventDelivery eventDelivery;
    private final DataSpaceEventsProperties properties;
    private final GeneralProperties generalProperties;
    private final Clock clock;

    @Write
    public HttpResponse<PublishedEvent> publish(@Nullable String type, @Nullable String subject, @Nullable Object data,
                                                @Nullable String id) {
        if (type == null || type.isBlank()) {
            return HttpResponse.badRequest();
        }
        String source = properties.ownId(generalProperties);
        // receivers reject an event without source, after the 202 the caller would never learn about the drop
        if (source == null || source.isBlank()) {
            log.error("Cannot publish event of type {}: neither dataspace.events.own-participant-id nor general.did is set.", type);
            return HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE);
        }
        DataSpaceEventVO event = new DataSpaceEventVO()
                .specversion(DataSpaceEventVO.Specversion._1_0)
                .id(id == null || id.isBlank() ? UUID.randomUUID().toString() : id)
                .source(source)
                .type(type)
                .subject(subject)
                .time(clock.instant())
                .dataspaceid(properties.getDataspaceId())
                .datacontenttype(CONTENT_TYPE)
                .data(plain(data));
        int subscriptions = eventDelivery.publish(event);
        log.info("Published event {} of type {} to {} subscriptions.", event.getId(), type, subscriptions);
        return HttpResponse.accepted().body(new PublishedEvent(event.getId(), subscriptions));
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
