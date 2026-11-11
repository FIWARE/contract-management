package org.fiware.iam.dataspace;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.exceptions.ConfigurationException;
import io.micronaut.core.value.PropertyResolver;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.DataSpaceEventsProperties;
import org.fiware.iam.configuration.PublisherEntryCheck;
import org.fiware.iam.configuration.PublisherEntryCheck.Finding;
import org.fiware.iam.configuration.PublisherEntryCheck.Severity;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Fails startup for a publisher entry that silently loses its participant.removed handling (see {@link PublisherEntryCheck}).
 * Not gated by the participant.removed condition, since a dropped entry can turn that condition false.
 */
@Slf4j
@Context
@Requires(condition = DataSpaceEventsProperties.ListenerCondition.class)
public class PublisherEntriesGuard {

    public PublisherEntriesGuard(DataSpaceEventsProperties properties, Environment environment) {
        List<Finding> findings = findings(properties, environment);
        findings.stream()
                .filter(finding -> finding.severity() == Severity.WARN)
                .forEach(finding -> log.warn(finding.message()));
        String fatal = findings.stream()
                .filter(finding -> finding.severity() == Severity.FATAL)
                .map(Finding::message)
                .collect(Collectors.joining(" "));
        if (!fatal.isEmpty()) {
            throw new ConfigurationException(fatal);
        }
    }

    static List<Finding> findings(DataSpaceEventsProperties properties, PropertyResolver resolver) {
        return PublisherEntryCheck.check(resolver.getProperty(PublisherEntryCheck.PUBLISHERS_KEY, List.class).orElse(null),
                properties.getPublishers());
    }
}
