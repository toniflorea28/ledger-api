package com.toniflorea.ledger.application.event;

import com.toniflorea.ledger.domain.transaction.Entry;
import com.toniflorea.ledger.domain.transaction.Transaction;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionRecordedPayload(UUID eventId, String eventType, Instant occurredAt,
		TransactionPayload transaction) {

	public record TransactionPayload(UUID id, String description, String currency, Instant recordedAt, UUID reverses,
			List<EntryPayload> entries) {
	}

	public record EntryPayload(UUID accountId, String direction, String amount, String balanceAfter) {
	}

	public static TransactionRecordedPayload from(TransactionRecordedEvent event) {
		Transaction tx = event.transaction();
		return new TransactionRecordedPayload(event.eventId(), TransactionRecordedEvent.TYPE, event.occurredAt(),
				new TransactionPayload(tx.id().value(), tx.description(), tx.currency().name(), tx.recordedAt(),
						tx.reverses() == null ? null : tx.reverses().value(),
						tx.entries().stream().map(TransactionRecordedPayload::entry).toList()));
	}

	private static EntryPayload entry(Entry entry) {
		return new EntryPayload(entry.accountId().value(), entry.direction().name(), entry.amount().toDecimalString(),
				entry.balanceAfter().toDecimalString());
	}
}
