package org.fiware.iam.configuration;


import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import lombok.Data;

import java.net.URI;
import java.util.List;
import java.util.Objects;

@Data
@Introspected
@ConfigurationProperties("oid4vp")
public class Oid4VpConfiguration {

    private boolean enabled = false;
    private Holder holder;
    private ProxyConfig proxyConfig;
    private String credentialsFolder;
    private boolean enableRevocation = false;
    // list of trust-anchors to be used in addition to the system truststore.
    private List<String> trustAnchors;

    @ConfigurationProperties("holder")
    @Introspected
    public static record Holder(URI holderId, String keyType, String keyPath, String signatureAlgorithm) {
    }

    @ConfigurationProperties("proxyConfig")
    @Introspected
    public static record ProxyConfig(boolean useProxy, @Nullable String proxyHost, @Nullable Integer proxyPort) {
        public ProxyConfig {
            if (useProxy) {
                Objects.requireNonNull(proxyHost, "proxyHost is required when useProxy is true");
                Objects.requireNonNull(proxyPort, "proxyPort is required when useProxy is true");
            }
        }
    }


    public static class Oid4VpCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(Oid4VpConfiguration.class)
                    .isEnabled();
        }
    }

}
