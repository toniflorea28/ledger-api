package com.toniflorea.ledger.application.event;

import com.toniflorea.ledger.domain.transaction.Transaction;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TransactionRecordedEvent(UUID eventId, Instant occurredAt, Transaction transaction,
		Map<String, String> headers) {

	public static final String TYPE = "ledger.transaction-recorded";

	public TransactionRecordedEvent {
		headers = Map.copyOf(headers);
	}

	public TransactionRecordedEvent(UUID eventId, Instant occurredAt, Transaction transaction) {
		this(eventId, occurredAt, transaction, Map.of());
	}
}
