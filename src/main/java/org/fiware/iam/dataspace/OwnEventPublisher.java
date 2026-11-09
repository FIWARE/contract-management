package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.GeneralProperties;
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
        // read once: dataspace.events is refreshable, so a second read could find the source emptied
        String source = properties.ownId(generalProperties);
        if (!present(source)) {
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

    /**
     * Whether an own participant id is configured, i.e. whether {@link #publish} would publish at all.
     */
    public boolean hasSource() {
        return present(properties.ownId(generalProperties));
    }

    private static boolean present(@Nullable String source) {
        return source != null && !source.isBlank();
    }

    /**
     * Answer to a publish: the event id and how many subscriptions it was queued for.
     */
    @Introspected
    public record PublishedEvent(String id, int subscriptions) {
    }
}
