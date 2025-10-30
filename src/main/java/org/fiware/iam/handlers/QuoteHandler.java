package org.fiware.iam.handlers;

import io.micronaut.http.HttpResponse;
import org.fiware.iam.tmforum.quote.model.QuoteVO;
import reactor.core.publisher.Mono;

/**
 * Handler for all events around TMForum Quotes
 */
public interface QuoteHandler {

    /**
     * Handle the creation of a Quote
     */
    Mono<HttpResponse<?>> handleQuoteCreation(QuoteVO quoteVO);

    /**
     * Handle the state changes of a Quote
     */
    Mono<HttpResponse<?>> handleQuoteStateChange(QuoteVO quoteVO);

    /**
     * Handle the deletion of a Quote
     */
    Mono<HttpResponse<?>> handleQuoteDeletion(QuoteVO quoteVO);

}
