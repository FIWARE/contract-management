package org.fiware.iam;

import org.fiware.iam.tmforum.productcatalog.model.CatalogVO;
import org.fiware.rainbow.model.NewCatalogVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Mapper for objects used in the Rainbow API
 */
@Mapper(componentModel = "jsr330")
public interface RainbowMapper {

	@Mapping(target = "dctColonTitle", source = "name")
	@Mapping(target = "atId", source = "id")
	NewCatalogVO map(CatalogVO catalogVO);

}
