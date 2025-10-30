package org.fiware.iam.handlers;

import io.micronaut.http.HttpResponse;
import org.fiware.iam.tmforum.productorder.model.ProductOrderVO;
import reactor.core.publisher.Mono;

/**
 * Handler for all events around TMForum ProductOrders
 */
public interface ProductOrderHandler {

    /**
     * Handle the completion of a product order
     *
     * @param customerId     - TMForum ID of the customer related to the order
     * @param productOrderVO - the completed order
     */
    Mono<HttpResponse<?>> handleProductOrderComplete(String customerId, ProductOrderVO productOrderVO);

    /**
     * Handle the stop of a product order
     *
     * @param customerId     - TMForum ID of the customer related to the order
     * @param productOrderVO - the completed order
     */
    Mono<HttpResponse<?>> handleProductOrderStop(String customerId, ProductOrderVO productOrderVO);

    /**
     * Handle the negotiation of a product order
     *
     * @param customerId     - TMForum ID of the customer related to the order
     * @param productOrderVO - the completed order
     */
    Mono<HttpResponse<?>> handleProductOrderNegotiation(String customerId, ProductOrderVO productOrderVO);

}
