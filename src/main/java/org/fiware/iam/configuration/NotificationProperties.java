package org.fiware.iam.configuration;


import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties("notification")
public class NotificationProperties {

    /**
     * If enabled, the contract management will subscribe to notifications at the TMForum
     */
    private boolean enabled = true;

    /**
     * Host that the contract-management is reachable at. Will be used for registering the call back
     */
    private String host = "contract-management";

    private List<NotificationConfig> entities = new ArrayList<>();


    public static class NotificationCondition implements Condition {

        @Override
        public boolean matches(ConditionContext context) {
            return context.getBean(NotificationProperties.class)
                    .isEnabled();
        }
    }

}

