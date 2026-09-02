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
    /**
     * Enables reading the policy and credential configuration from the ServiceSpecifications a
     * ProductSpecification is composed of, in addition to the ProductSpecification itself. Off by
     * default: it adds one TMForum read per referenced ServiceSpecification to the activation of an
     * order, and it changes which configuration an order activates.
     */
    private boolean enableSpecificationComposition = false;
    /**
     * How many specification levels below the ordered ProductSpecification are resolved when
     * composition is enabled. The default of 2 covers a ProductSpecification and the
     * ServiceSpecifications it references; bundled ProductSpecifications consume the same budget.
     * References below the limit are skipped with a warning.
     */
    private int specificationCompositionMaxDepth = 2;

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
