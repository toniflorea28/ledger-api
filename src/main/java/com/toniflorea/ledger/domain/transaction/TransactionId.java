package com.toniflorea.ledger.domain.transaction;

import java.util.Objects;
import java.util.UUID;

public record TransactionId(UUID value) {

	public TransactionId {
		Objects.requireNonNull(value, "value");
	}

	public static TransactionId of(UUID value) {
		return new TransactionId(value);
	}

	public static TransactionId of(String value) {
		return new TransactionId(UUID.fromString(value));
	}

	@Override
	public String toString() {
		return value.toString();
	}
}
