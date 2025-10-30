package org.fiware.iam.handlers;

import io.micronaut.http.HttpResponse;
import org.fiware.iam.tmforum.productcatalog.model.CatalogVO;
import reactor.core.publisher.Mono;


/**
 * Handle all events around TMForum Catalogs.
 */
public interface CatalogHandler {

    /**
     * Handle the creation of Catalog
     */
    Mono<HttpResponse<?>> handleCatalogCreation(CatalogVO catalogVO);

    /**
     * Handle state changes of catalogs
     */
    Mono<HttpResponse<?>> handleCatalogStateChange(CatalogVO catalogVO);

    /**
     * Handle the deletion of a catalog
     */
    Mono<HttpResponse<?>> handleCatalogDeletion(CatalogVO catalogVO);
}
