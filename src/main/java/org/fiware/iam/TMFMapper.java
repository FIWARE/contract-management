package org.fiware.iam;

import org.fiware.iam.tmforum.agreement.model.RelatedPartyTmfVO;
import org.fiware.iam.tmforum.productorder.model.RelatedPartyVO;
import org.mapstruct.Mapper;
/**
 * Mapper for objects between TMForum APIs
 */
@Mapper(componentModel = "jsr330")
public interface TMFMapper {

	RelatedPartyTmfVO map(RelatedPartyVO relatedPartyVO);
}
