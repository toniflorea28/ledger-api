package com.toniflorea.ledger.domain.error;

import java.util.List;

public class LedgerException extends RuntimeException {

	private final List<LedgerError> errors;

	public LedgerException(List<LedgerError> errors) {
		super(describe(errors));
		if (errors.isEmpty()) {
			throw new IllegalArgumentException("a LedgerException needs at least one error");
		}
		this.errors = List.copyOf(errors);
	}

	public LedgerException(LedgerError error) {
		this(List.of(error));
	}

	public static LedgerException of(ErrorCode code, String pointer, String detail) {
		return new LedgerException(LedgerError.of(code, pointer, detail));
	}

	public static LedgerException of(ErrorCode code, String detail) {
		return new LedgerException(LedgerError.of(code, detail));
	}

	public List<LedgerError> errors() {
		return errors;
	}

	public ErrorCode.Kind kind() {
		return errors.get(0).code().kind();
	}

	public boolean has(ErrorCode code) {
		return errors.stream().anyMatch(e -> e.code() == code);
	}

	private static String describe(List<LedgerError> errors) {
		if (errors.size() == 1) {
			return errors.get(0).detail();
		}
		return errors.size() + " errors: " + errors.stream().map(LedgerError::detail).toList();
	}
}
