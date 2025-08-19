package org.fiware.iam;

import io.micronaut.http.HttpResponse;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.odrl.pap.api.DefaultApiClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Responsible for handling connections with the PAP.
 */
@Singleton
@RequiredArgsConstructor
@Slf4j
public class PAPAdapter {

	private final DefaultApiClient papClient;

	public Mono<Boolean> createPolicy(Map<String, Object> policy) {
		return papClient.createPolicy(policy).map(HttpResponse::code).map(code -> code >= 200 && code < 300);
	}
}
