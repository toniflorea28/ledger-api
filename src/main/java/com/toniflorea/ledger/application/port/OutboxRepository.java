package com.toniflorea.ledger.application.port;

import com.toniflorea.ledger.application.event.TransactionRecordedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface OutboxRepository {

	record Entry(long id, UUID eventId, String eventType, UUID aggregateId, String partitionKey, String payload,
			Map<String, String> headers) {
	}

	void append(TransactionRecordedEvent event);

	List<Entry> fetchUnpublished(int limit);

	void markPublished(List<Long> ids, Instant publishedAt);

	long countUnpublished();
}
