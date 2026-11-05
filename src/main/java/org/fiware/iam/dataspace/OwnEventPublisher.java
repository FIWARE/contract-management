package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.dataspace.PublishEndpoint.PublishedEvent;
import org.fiware.iam.events.model.DataSpaceEventVO;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static io.micronaut.http.MediaType.APPLICATION_JSON;

/**
 * Publishes events of this instance to the external subscribers, filling in the envelope (source, id, time, dataspaceid).
 * Returns empty without publishing when no source is configured, since receivers reject an event without one.
 */
@Singleton
@RequiredArgsConstructor
@Requires(condition = DataSpaceEventsProperties.PublisherCondition.class)
public class OwnEventPublisher {

    private final EventDelivery eventDelivery;
    private final DataSpaceEventsProperties properties;
    private final GeneralProperties generalProperties;
    private final Clock clock;

    public Optional<PublishedEvent> publish(String type, @Nullable String subject, @Nullable Object data,
                                            @Nullable String id) {
        String source = properties.ownId(generalProperties);
        if (source == null || source.isBlank()) {
            return Optional.empty();
        }
        DataSpaceEventVO event = new DataSpaceEventVO()
                .specversion(DataSpaceEventVO.Specversion._1_0)
                .id(id == null || id.isBlank() ? UUID.randomUUID().toString() : id)
                .source(source)
                .type(type)
                .subject(subject)
                .time(clock.instant())
                .dataspaceid(properties.getDataspaceId())
                .datacontenttype(APPLICATION_JSON)
                .data(data);
        return Optional.of(new PublishedEvent(event.getId(), eventDelivery.publish(event)));
    }
}
