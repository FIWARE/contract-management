package org.fiware.iam.dsp;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.logging.DownstreamError;


@Requires(condition = GeneralProperties.RainbowCondition.class)
@Singleton
@Slf4j
@RequiredArgsConstructor
public class RainbowInitializer {

	private static final String PROVIDER_ROLE = "Provider";

	private final RainbowAdapter rainbowAdapter;
	private final GeneralProperties generalProperties;

	@EventListener
	public void initializeProvider(StartupEvent startupEvent) {
		rainbowAdapter
				.isParticipant(generalProperties.getDid())
				.filter(r -> !r)
				.flatMap(r -> rainbowAdapter.createParticipant(generalProperties.getDid(), PROVIDER_ROLE))
				.subscribe(
						participant -> log.info("Registered {} as provider participant at Rainbow.", participant),
						e -> log.error("Could not register {} as provider participant at Rainbow, DSP negotiations will fail: {}",
								generalProperties.getDid(), DownstreamError.reason(e), e));
	}
}
