package org.fiware.iam.dsp;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.configuration.GeneralProperties;
import org.fiware.iam.handlers.CatalogHandler;
import org.fiware.iam.tmforum.productcatalog.model.CatalogVO;
import org.fiware.rainbow.api.CatalogApiClient;
import org.fiware.rainbow.model.NewCatalogVO;
import reactor.core.publisher.Mono;

/**
 * Handler to manage catalog events and exchange them with Rainbow.
 */
@Requires(condition = GeneralProperties.RainbowCondition.class)
@RequiredArgsConstructor
@Singleton
@Slf4j
public class RainbowCatalogHandler implements CatalogHandler {

    private final CatalogApiClient catalogApiClient;
    private final RainbowMapper rainbowMapper;

    @Override
    public Mono<HttpResponse<?>> handleCatalogCreation(CatalogVO catalogVO) {
        NewCatalogVO rainbowCatalog = rainbowMapper.map(catalogVO);
        return catalogApiClient.createCatalog(rainbowCatalog)
                .onErrorMap(t -> new IllegalArgumentException("Was not able create the catalog %s".formatted(rainbowCatalog), t))
                .map(HttpResponse::ok);
    }

    @Override
    public Mono<HttpResponse<?>> handleCatalogStateChange(CatalogVO catalogVO) {
        return catalogApiClient.updateCatalogById(catalogVO.getId(), rainbowMapper.map(catalogVO)).map(res -> {
            if (res.getStatus().getCode() >= 200 && res.getStatus().getCode() < 300) {
                return HttpResponse.noContent();
            }
            return HttpResponse.status(HttpStatus.BAD_GATEWAY);
        });
    }

    @Override
    public Mono<HttpResponse<?>> handleCatalogDeletion(CatalogVO catalogVO) {
        return catalogApiClient.deleteCatalogById(catalogVO.getId()).map(res -> {
            if (res.getStatus().getCode() >= 200 && res.getStatus().getCode() < 300) {
                return HttpResponse.noContent();
            }
            return HttpResponse.status(HttpStatus.BAD_GATEWAY);
        });
    }
}
