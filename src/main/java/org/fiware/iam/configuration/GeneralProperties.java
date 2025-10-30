package org.fiware.iam.configuration;


import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import lombok.Data;

@Data
@ConfigurationProperties("general")
public class GeneralProperties {

    /**
     * DID of the organization running this instance.
     */
    private String did;

    /**
     * Basepath of all controllers
     */
    private String basePath = "/";
    /**
     * Enables integration with the (local) ODRL-PAP. http.services.odrl needs to be configured
     * towards the ODRL-PAP.
     */
    private boolean enableOdrlPap = true;
    /**
     * Enables integration with the (local) Trusted Issuers List. http.services.trusted-issuers-list needs to be configured
     * towards the Trusted Issuers List.
     */
    private boolean enableTrustedIssuersList = true;
    /**
     * Enables integration with the TMForum API.
     */
    private boolean enableTmForum = true;
    /**
     * Requires TMForum integration to be enabled and the http.service.rainbow properly configured
     */
    private boolean enableRainbow = true;
    /**
     * Enable integration with a central marketplace. Requires OdrlPap and TrustedIssuersList integration to be enabled.
     */
    private boolean enableCentralMarketplace = true;

    public static class CentralMarketplaceCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(GeneralProperties.class)
                    .isEnableCentralMarketplace();
        }
    }

    public static class RainbowCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(GeneralProperties.class)
                    .isEnableRainbow();
        }
    }

    public static class PapCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(GeneralProperties.class)
                    .isEnableOdrlPap();
        }
    }

    public static class TilCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(GeneralProperties.class)
                    .isEnableTrustedIssuersList();
        }
    }

    public static class TmForumCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(GeneralProperties.class)
                    .isEnableTmForum();
        }
    }
}
