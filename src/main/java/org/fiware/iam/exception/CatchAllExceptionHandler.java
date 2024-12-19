package org.fiware.iam.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.tmforum.productorder.model.ErrorVO;

import java.time.format.DateTimeParseException;

/**
 * Handler to catch and log all unexpected exceptions and translate them into a proper 500 response.
 */
@Produces
@Singleton
@Requires(classes = {Exception.class, ExceptionHandler.class})
@Slf4j
public class CatchAllExceptionHandler implements ExceptionHandler<Exception, HttpResponse<ErrorVO>> {

    @Override
    public HttpResponse<ErrorVO> handle(HttpRequest request, Exception exception) {
        log.warn("Received unexpected exception {} for request {}.", exception.getMessage(), request.getUri(), exception);
        if (exception instanceof DateTimeParseException dateTimeParseException) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST)
                    .body(
                            new ErrorVO().status(HttpStatus.BAD_REQUEST.toString())
                                    .reason(HttpStatus.BAD_REQUEST.getReason())
                                    .message(String.format("Request could not be answered due to an invalid date: %s.",
                                            dateTimeParseException.getParsedString())));
        }
        if (exception instanceof TMForumException tmForumException) {
            return HttpResponse.status(HttpStatus.BAD_GATEWAY)
                    .body(
                            new ErrorVO().status(HttpStatus.BAD_GATEWAY.toString())
                                    .reason(HttpStatus.BAD_GATEWAY.getReason())
                                    .message(String.format("Request could not be answered due to error in downstream service: %s.",
                                            tmForumException.getMessage())));
        }
        if (exception instanceof IllegalArgumentException illegalArgumentException) {
            return HttpResponse.status(HttpStatus.BAD_REQUEST)
                    .body(
                            new ErrorVO().status(HttpStatus.BAD_REQUEST.toString())
                                    .reason(HttpStatus.BAD_REQUEST.getReason())
                                    .message(String.format(illegalArgumentException.getMessage())));
        }
        return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorVO().status(HttpStatus.INTERNAL_SERVER_ERROR.toString())
                        .reason(HttpStatus.INTERNAL_SERVER_ERROR.getReason())
                        .message("Request could not be answered due to an unexpected internal error."));
    }
}
