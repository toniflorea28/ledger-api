package com.toniflorea.ledger.adapter.inbound.rest;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerError;
import com.toniflorea.ledger.domain.error.LedgerException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	@ExceptionHandler(LedgerException.class)
	ResponseEntity<ProblemDetail> ledgerException(LedgerException ex) {
		HttpStatus status = switch (ex.kind()) {
			case MALFORMED -> HttpStatus.BAD_REQUEST;
			case VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
			case CONFLICT -> HttpStatus.CONFLICT;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
		};
		return ResponseEntity.status(status).body(problem(status, ex.errors()));
	}

	@ExceptionHandler(NotImplemented.class)
	ResponseEntity<ProblemDetail> notImplemented(NotImplemented ex) {
		HttpStatus status = HttpStatus.NOT_IMPLEMENTED;
		return ResponseEntity.status(status)
				.body(problem(status, List.of(LedgerError.of(ErrorCode.MALFORMED_REQUEST, ex.getMessage()))));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> unexpected(Exception ex) {
		log.error("unhandled exception", ex);
		HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
		return ResponseEntity.status(status).body(problem(status,
				List.of(LedgerError.of(ErrorCode.MALFORMED_REQUEST, "the ledger could not process the request"))));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpStatus status = HttpStatus.valueOf(statusCode.value());
		return ResponseEntity.status(status).headers(headers).body(problem(status, describe(ex, status)));
	}

	private static List<LedgerError> describe(Exception ex, HttpStatus status) {
		if (ex instanceof MethodArgumentNotValidException invalid) {
			List<LedgerError> errors = new ArrayList<>();
			for (FieldError field : invalid.getBindingResult().getFieldErrors()) {
				errors.add(LedgerError.of(ErrorCode.MALFORMED_REQUEST, pointer(field.getField()),
						field.getField() + " " + field.getDefaultMessage()
								+ (field.getRejectedValue() == null ? "" : ", got '" + field.getRejectedValue() + "'")));
			}
			invalid.getBindingResult().getGlobalErrors().forEach(global -> errors
					.add(LedgerError.of(ErrorCode.MALFORMED_REQUEST, "", String.valueOf(global.getDefaultMessage()))));
			return errors.isEmpty() ? List.of(LedgerError.of(ErrorCode.MALFORMED_REQUEST, "invalid request")) : errors;
		}
		if (ex instanceof MissingRequestHeaderException missing && IDEMPOTENCY_KEY.equalsIgnoreCase(missing.getHeaderName())) {
			return List.of(LedgerError.of(ErrorCode.IDEMPOTENCY_KEY_MISSING,
					"the " + IDEMPOTENCY_KEY + " header is required on every POST"));
		}
		if (ex instanceof HandlerMethodValidationException validation) {
			boolean idempotencyKey = validation.getParameterValidationResults().stream()
					.map(result -> result.getMethodParameter().getParameterAnnotation(RequestHeader.class))
					.anyMatch(header -> header != null && IDEMPOTENCY_KEY.equalsIgnoreCase(header.value()));
			String detail = validation.getParameterValidationResults().stream()
					.flatMap(result -> result.getResolvableErrors().stream())
					.map(error -> String.valueOf(error.getDefaultMessage()))
					.collect(Collectors.joining("; "));
			return List.of(LedgerError.of(idempotencyKey ? ErrorCode.IDEMPOTENCY_KEY_MISSING : ErrorCode.MALFORMED_REQUEST,
					detail.isEmpty() ? "invalid request parameters" : detail));
		}
		ErrorCode code = status == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND : ErrorCode.MALFORMED_REQUEST;
		return List.of(LedgerError.of(code, rootMessage(ex)));
	}

	private static ProblemDetail problem(HttpStatus status, List<LedgerError> errors) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create("urn:ledger:problem:" + typeOf(status)));
		problem.setTitle(titleOf(status));
		problem.setDetail(errors.size() == 1 ? errors.get(0).detail() : errors.size() + " problems with the request");
		problem.setProperty("errors", errors.stream().map(ProblemHandler::toMap).toList());
		return problem;
	}

	private static Map<String, Object> toMap(LedgerError error) {
		return error.pointer() == null
				? Map.of("code", error.code().name(), "detail", error.detail())
				: Map.of("code", error.code().name(), "detail", error.detail(), "pointer", error.pointer());
	}

	private static String typeOf(HttpStatus status) {
		return switch (status) {
			case BAD_REQUEST -> "malformed";
			case NOT_FOUND -> "not-found";
			case CONFLICT -> "conflict";
			case UNPROCESSABLE_ENTITY -> "refused";
			case NOT_IMPLEMENTED -> "not-implemented";
			case INTERNAL_SERVER_ERROR -> "internal";
			default -> status.name().toLowerCase().replace('_', '-');
		};
	}

	private static String titleOf(HttpStatus status) {
		return switch (status) {
			case BAD_REQUEST -> "Malformed request";
			case NOT_FOUND -> "Not found";
			case CONFLICT -> "Conflicts with the current state";
			case UNPROCESSABLE_ENTITY -> "Refused by a ledger rule";
			case NOT_IMPLEMENTED -> "Not implemented";
			case INTERNAL_SERVER_ERROR -> "Internal error";
			default -> status.getReasonPhrase();
		};
	}

	private static String pointer(String field) {
		return "/" + field.replaceAll("\\[(\\d+)]", "/$1").replace('.', '/');
	}

	private static String rootMessage(Throwable ex) {
		Throwable root = ex;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		String message = root.getMessage();
		return message == null ? ex.getClass().getSimpleName() : message.lines().findFirst().orElse(message);
	}
}
