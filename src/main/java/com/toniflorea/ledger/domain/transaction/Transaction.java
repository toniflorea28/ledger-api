package com.toniflorea.ledger.domain.transaction;

import com.toniflorea.ledger.domain.money.Currency;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Transaction(TransactionId id, String description, Currency currency, Instant recordedAt,
		List<Entry> entries, TransactionId reverses) {

	public Transaction {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(currency, "currency");
		Objects.requireNonNull(recordedAt, "recordedAt");
		entries = List.copyOf(entries);
	}

	public boolean isReversal() {
		return reverses != null;
	}
}
