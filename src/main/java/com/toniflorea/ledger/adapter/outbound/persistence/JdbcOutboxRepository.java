package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.event.TransactionRecordedEvent;
import com.toniflorea.ledger.application.event.TransactionRecordedPayload;
import com.toniflorea.ledger.application.port.OutboxRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcOutboxRepository implements OutboxRepository {

	private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final JsonMapper json;

	public JdbcOutboxRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	public void append(TransactionRecordedEvent event) {
		jdbc.sql("""
				INSERT INTO outbox_events (event_id, event_type, aggregate_id, partition_key, payload, headers, created_at)
				VALUES (:eventId, :eventType, :aggregateId, :partitionKey, :payload, :headers, :createdAt)
				""")
				.param("eventId", Uuids.toBytes(event.eventId()))
				.param("eventType", TransactionRecordedEvent.TYPE)
				.param("aggregateId", Uuids.toBytes(event.transaction().id().value()))
				.param("partitionKey", event.transaction().currency().name())
				.param("payload", json.writeValueAsString(TransactionRecordedPayload.from(event)))
				.param("headers", event.headers().isEmpty() ? null : json.writeValueAsString(event.headers()))
				.param("createdAt", Timestamp.from(event.occurredAt()))
				.update();
	}

	@Override
	public List<Entry> fetchUnpublished(int limit) {
		return jdbc.sql("""
				SELECT id, event_id, event_type, aggregate_id, partition_key, payload, headers
				FROM outbox_events WHERE published_at IS NULL ORDER BY id LIMIT :limit
				""")
				.param("limit", limit)
				.query(this::map)
				.list();
	}

	@Override
	public void markPublished(List<Long> ids, Instant publishedAt) {
		if (ids.isEmpty()) {
			return;
		}
		jdbc.sql("UPDATE outbox_events SET published_at = :at WHERE id IN (:ids) AND published_at IS NULL")
				.param("at", Timestamp.from(publishedAt))
				.param("ids", ids)
				.update();
	}

	@Override
	public long countUnpublished() {
		return jdbc.sql("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL").query(Long.class).single();
	}

	private Entry map(ResultSet rs, int rowNum) throws SQLException {
		String headers = rs.getString("headers");
		return new Entry(rs.getLong("id"), Uuids.fromBytes(rs.getBytes("event_id")), rs.getString("event_type"),
				Uuids.fromBytes(rs.getBytes("aggregate_id")), rs.getString("partition_key"), rs.getString("payload"),
				headers == null ? Map.of() : json.readValue(headers, HEADERS));
	}
}
