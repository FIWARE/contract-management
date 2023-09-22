package org.fiware.iam.til;

import org.fiware.iam.til.model.ClaimVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "jsr330")
public interface TrustedIssuerConfigMapper {

    @Mapping(target = "name", source = "target")
    @Mapping(target = "allowedValues", source = "roles")
    ClaimVO map(Claim claim);
}
