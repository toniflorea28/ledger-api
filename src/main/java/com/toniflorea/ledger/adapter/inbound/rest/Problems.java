package com.toniflorea.ledger.adapter.inbound.rest;

import com.toniflorea.ledger.domain.error.LedgerError;
import com.toniflorea.ledger.domain.error.LedgerException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

final class Problems {

	private Problems() {
	}

	static HttpStatus statusOf(LedgerException ex) {
		return switch (ex.kind()) {
			case MALFORMED -> HttpStatus.BAD_REQUEST;
			case VALIDATION -> HttpStatus.UNPROCESSABLE_ENTITY;
			case CONFLICT -> HttpStatus.CONFLICT;
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
		};
	}

	static ProblemDetail problem(HttpStatus status, List<LedgerError> errors) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setType(URI.create("urn:ledger:problem:" + typeOf(status)));
		problem.setTitle(titleOf(status));
		problem.setDetail(detailOf(errors));
		problem.setProperty("errors", errors.stream().map(Problems::toMap).toList());
		return problem;
	}

	static Map<String, Object> render(LedgerException ex, String instance) {
		HttpStatus status = statusOf(ex);
		Map<String, Object> problem = new LinkedHashMap<>();
		problem.put("type", "urn:ledger:problem:" + typeOf(status));
		problem.put("title", titleOf(status));
		problem.put("status", status.value());
		problem.put("detail", detailOf(ex.errors()));
		problem.put("instance", instance);
		problem.put("errors", ex.errors().stream().map(Problems::toMap).toList());
		return problem;
	}

	private static String detailOf(List<LedgerError> errors) {
		return errors.size() == 1 ? errors.get(0).detail() : errors.size() + " problems with the request";
	}

	private static Map<String, Object> toMap(LedgerError error) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("code", error.code().name());
		map.put("detail", error.detail());
		if (error.pointer() != null) {
			map.put("pointer", error.pointer());
		}
		return map;
	}

	static String typeOf(HttpStatus status) {
		return switch (status) {
			case BAD_REQUEST -> "malformed";
			case NOT_FOUND -> "not-found";
			case CONFLICT -> "conflict";
			case UNPROCESSABLE_ENTITY -> "refused";
			case INTERNAL_SERVER_ERROR -> "internal";
			default -> status.name().toLowerCase().replace('_', '-');
		};
	}

	static String titleOf(HttpStatus status) {
		return switch (status) {
			case BAD_REQUEST -> "Malformed request";
			case NOT_FOUND -> "Not found";
			case CONFLICT -> "Conflicts with the current state";
			case UNPROCESSABLE_ENTITY -> "Refused by a ledger rule";
			case INTERNAL_SERVER_ERROR -> "Internal error";
			default -> status.getReasonPhrase();
		};
	}
}
