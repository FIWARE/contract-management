package org.fiware.iam.til;


import jakarta.inject.Singleton;
import org.fiware.iam.til.model.ClaimVO;

import java.util.Collections;
import java.util.List;

@Singleton
public class TrustedIssuerMapper {

    // Replace with input from notification event -> ProductOrderCreateEventVO
    public ClaimVO map(String targetServiceDID, List<Object> roles){
        return new ClaimVO().name(targetServiceDID).allowedValues(roles);
    }
}
