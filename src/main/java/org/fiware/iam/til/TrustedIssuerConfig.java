package org.fiware.iam.til;

import io.micronaut.context.annotation.ConfigurationProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
@Data
@ConfigurationProperties("general.til")
public class TrustedIssuerConfig {

        private String credentialType;
        private List<Claim> claims = new ArrayList<>();

        @Data
        public class Claim{
                private String target;
                private List<String> roles = new ArrayList<>();
        }
}
