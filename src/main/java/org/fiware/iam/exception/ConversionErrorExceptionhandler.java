package org.fiware.iam.exception;

import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.productorder.model.ErrorVO;

@Produces
@Singleton
@Primary
@Requires(classes = {ConversionErrorException.class, ExceptionHandler.class})
@Slf4j
public class ConversionErrorExceptionhandler implements ExceptionHandler<ConversionErrorException, HttpResponse<ErrorVO>> {

    @Override
    public HttpResponse<ErrorVO> handle(HttpRequest request, ConversionErrorException exception) {
        log.warn("Received unexpected exception {} for request {}.", exception.getMessage(), request, exception);
        return HttpResponse.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorVO().status(HttpStatus.BAD_REQUEST.toString())
                        .reason(HttpStatus.BAD_REQUEST.getReason())
                        .message("Request could not be answered due to faulty input."));
    }
}
