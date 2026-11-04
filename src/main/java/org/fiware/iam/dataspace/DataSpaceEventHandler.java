package org.fiware.iam.dataspace;

import org.fiware.iam.events.model.DataSpaceEventVO;
import reactor.core.publisher.Mono;

/**
 * Handler for authorized data space events; the registry of all implementations decides which types are known.
 */
public interface DataSpaceEventHandler {

    /**
     * Return if the given handler implementation supports the given event type
     */
    boolean isEventTypeSupported(String type);

    /**
     * Handle the event. An error makes the publisher's retry be handled again.
     */
    Mono<Void> handleEvent(DataSpaceEventVO event);
}
