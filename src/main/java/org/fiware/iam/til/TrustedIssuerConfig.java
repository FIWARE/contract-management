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
}
