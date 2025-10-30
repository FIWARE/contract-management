package org.fiware.iam.handlers;

import io.micronaut.http.HttpResponse;
import org.fiware.iam.tmforum.productcatalog.model.ProductOfferingVO;
import reactor.core.publisher.Mono;

/**
 * Handler for all events around TMForum ProductOfferings
 */
public interface ProductOfferingHandler {

    /**
     * Handle the creation of a product offering
     */
    Mono<HttpResponse<?>> handleOfferingCreation(ProductOfferingVO productOfferingVO);

    /**
     * Handle state changes of product offerings
     */
    Mono<HttpResponse<?>> handleOfferingStateChange(ProductOfferingVO productOfferingVO);

    /**
     * Handle the deletion of a product offering
     */
    Mono<HttpResponse<?>> handleOfferingDeletion(ProductOfferingVO productOfferingVO);

}
