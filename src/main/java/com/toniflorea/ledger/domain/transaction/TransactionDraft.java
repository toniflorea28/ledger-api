package com.toniflorea.ledger.domain.transaction;

import java.util.List;
import java.util.Objects;

public record TransactionDraft(String description, List<EntryDraft> entries, TransactionId reverses) {

	public TransactionDraft {
		Objects.requireNonNull(description, "description");
		entries = List.copyOf(entries);
	}

	public static TransactionDraft of(String description, List<EntryDraft> entries) {
		return new TransactionDraft(description, entries, null);
	}

	public boolean isReversal() {
		return reverses != null;
	}
}
