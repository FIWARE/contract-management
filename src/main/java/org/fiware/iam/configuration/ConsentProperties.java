package org.fiware.iam.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import lombok.Data;

/**
 * Configuration for making the agreements this component writes readable by the consent
 * integration.
 *
 * <p>The consent-facade projects a TM Forum agreement into the contract the consent-manager needs
 * for a privacy notice. To do that it reads characteristics the plain agreement does not carry:
 * {@code provider-id}/{@code consumer-id} (the participants' self-description URLs),
 * {@code signing-date} (which marks the contract concluded) and {@code policy} (the ODRL the consent
 * is scoped by). When {@link #enabled} those are written alongside the existing characteristics.
 *
 * <p>Off by default: a deployment without consent management must keep producing exactly the
 * agreements it produced before.
 */
@Data
@ConfigurationProperties("consent")
public class ConsentProperties {

    /** Whether agreements are enriched for the consent integration. */
    private boolean enabled = false;

    /**
     * Base URL the participants' self-description identifiers are built from, as
     * {@code <baseUrl>/participants/<organization-id>}.
     *
     * <p>It MUST equal the consent-facade's own {@code selfUrl}: those identifiers are written into
     * privacy notices and ISO 27560 consent receipts, and the consent-manager matches participants
     * on them, so a value that merely resolves is not enough - it has to be the same string the
     * facade mints. Enrichment is skipped when unset.
     */
    @Nullable
    private String selfDescriptionBaseUrl;

    /** Role marking the consuming party on the agreement's engaged parties. */
    private String consumerRole = "Consumer";

    /** Role marking the providing party on the agreement's engaged parties. */
    private String providerRole = "Provider";
}
