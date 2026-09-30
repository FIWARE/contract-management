package org.fiware.iam.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.fiware.iam.logging.DownstreamError;
import org.fiware.iam.tmforum.productorder.model.ErrorVO;

import java.time.format.DateTimeParseException;

/**
 * Handler to catch and log all exceptions and translate them into a proper error response.
 * <p>
 * Expected failures (known exception types, failed downstream calls) are logged as a single line with their
 * reason, only really unexpected ones are logged with their stack trace.
 */
@Produces
@Singleton
@Requires(classes = {Exception.class, ExceptionHandler.class})
@Slf4j
public class CatchAllExceptionHandler implements ExceptionHandler<Exception, HttpResponse<ErrorVO>> {

	@Override
	public HttpResponse<ErrorVO> handle(HttpRequest request, Exception exception) {
		if (exception instanceof DateTimeParseException dateTimeParseException) {
			return respond(request, HttpStatus.BAD_REQUEST,
					"Request could not be answered due to an invalid date: %s.".formatted(dateTimeParseException.getParsedString()),
					exception);
		}
		if (exception instanceof TMForumException) {
			return respond(request, HttpStatus.BAD_GATEWAY,
					"Request could not be answered due to error in downstream tmforum service: %s".formatted(DownstreamError.describe(exception)),
					exception);
		}
		if (exception instanceof TrustedIssuersException) {
			return respond(request, HttpStatus.BAD_GATEWAY,
					"Request could not be answered due to error in downstream trusted issuers list service: %s".formatted(DownstreamError.describe(exception)),
					exception);
		}
		if (exception instanceof RainbowException) {
			return respond(request, HttpStatus.BAD_GATEWAY,
					"Request could not be answered due to error in downstream rainbow service: %s".formatted(DownstreamError.describe(exception)),
					exception);
		}
		if (exception instanceof PapException) {
			return respond(request, HttpStatus.BAD_GATEWAY,
					"Request could not be answered due to error in downstream odrl-pap service: %s".formatted(DownstreamError.describe(exception)),
					exception);
		}
		if (exception instanceof HttpClientResponseException) {
			return respond(request, HttpStatus.BAD_GATEWAY,
					"Request could not be answered due to error in a downstream service: %s".formatted(DownstreamError.describe(exception)),
					exception);
		}
		if (exception instanceof IllegalArgumentException) {
			return respond(request, HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
		}
		log.error("Unexpected error while handling {} {}: {}", request.getMethod(), request.getUri(), DownstreamError.describe(exception), exception);
		return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ErrorVO().status(HttpStatus.INTERNAL_SERVER_ERROR.toString())
						.reason(HttpStatus.INTERNAL_SERVER_ERROR.getReason())
						.message("Request could not be answered due to an unexpected internal error."));
	}

	private HttpResponse<ErrorVO> respond(HttpRequest<?> request, HttpStatus status, String message, Exception exception) {
		log.warn("Answered {} {} with {}: {}", request.getMethod(), request.getUri(), status.getCode(), DownstreamError.describe(exception));
		return HttpResponse.status(status)
				.body(new ErrorVO().status(status.toString())
						.reason(status.getReason())
						.message(message));
	}
}
