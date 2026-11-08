package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.event.TransactionRecordedEvent;
import com.toniflorea.ledger.application.event.TransactionRecordedPayload;
import com.toniflorea.ledger.application.port.OutboxRepository;
import java.sql.Timestamp;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

	private final JdbcClient jdbc;
	private final JsonMapper json;

	public JdbcOutboxRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	public void append(TransactionRecordedEvent event) {
		jdbc.sql("""
				INSERT INTO outbox_events (event_id, event_type, aggregate_id, partition_key, payload, created_at)
				VALUES (:eventId, :eventType, :aggregateId, :partitionKey, :payload, :createdAt)
				""")
				.param("eventId", Uuids.toBytes(event.eventId()))
				.param("eventType", TransactionRecordedEvent.TYPE)
				.param("aggregateId", Uuids.toBytes(event.transaction().id().value()))
				.param("partitionKey", event.transaction().currency().name())
				.param("payload", json.writeValueAsString(TransactionRecordedPayload.from(event)))
				.param("createdAt", Timestamp.from(event.occurredAt()))
				.update();
	}
}
