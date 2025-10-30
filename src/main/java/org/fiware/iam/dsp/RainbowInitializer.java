package org.fiware.iam.dsp;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.exception.RainbowException;


@Requires(condition = GeneralProperties.RainbowCondition.class)
@Singleton
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
				.subscribe();
	}
}
