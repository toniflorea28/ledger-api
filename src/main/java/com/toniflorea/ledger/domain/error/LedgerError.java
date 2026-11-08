package com.toniflorea.ledger.domain.error;

import java.util.Objects;

public record LedgerError(ErrorCode code, String pointer, String detail) {

	public LedgerError {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(detail, "detail");
	}

	public static LedgerError of(ErrorCode code, String pointer, String detail) {
		return new LedgerError(code, pointer, detail);
	}

	public static LedgerError of(ErrorCode code, String detail) {
		return new LedgerError(code, null, detail);
	}
}
