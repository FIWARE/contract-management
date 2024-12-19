package org.fiware.iam;

import org.fiware.iam.tmforum.productcatalog.model.CatalogVO;
import org.fiware.iam.tmforum.productcatalog.model.ProductSpecificationVO;
import org.fiware.iam.tmforum.productcatalog.model.RelatedPartyVO;
import org.fiware.rainbow.model.NewCatalogVO;
import org.fiware.rainbow.model.NewDataserviceVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Optional;

@Mapper(componentModel = "jsr330")
public interface RainbowMapper {

	@Mapping(target = "dctColonTitle", source = "name")
	@Mapping(target = "atId", source = "id")
	NewCatalogVO map(CatalogVO catalogVO);

}
