package com.toniflorea.ledger.application.event;

import com.toniflorea.ledger.domain.transaction.Transaction;
import java.time.Instant;
import java.util.UUID;

public record TransactionRecordedEvent(UUID eventId, Instant occurredAt, Transaction transaction) {

	public static final String TYPE = "ledger.transaction-recorded";
}
