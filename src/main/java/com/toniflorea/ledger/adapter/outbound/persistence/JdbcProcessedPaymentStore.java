package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.port.ProcessedPaymentStore;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessedPaymentStore implements ProcessedPaymentStore {

	private final JdbcClient jdbc;

	public JdbcProcessedPaymentStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<TransactionId> find(String paymentId) {
		return jdbc.sql("SELECT transaction_id FROM processed_payments WHERE payment_id = :paymentId")
				.param("paymentId", paymentId)
				.query((rs, i) -> TransactionId.of(Uuids.fromBytes(rs.getBytes("transaction_id"))))
				.optional();
	}

	@Override
	public void insert(String paymentId, String eventId, TransactionId transactionId, Instant processedAt) {
		try {
			jdbc.sql("""
					INSERT INTO processed_payments (payment_id, event_id, transaction_id, processed_at)
					VALUES (:paymentId, :eventId, :transactionId, :processedAt)
					""")
					.param("paymentId", paymentId)
					.param("eventId", eventId)
					.param("transactionId", Uuids.toBytes(transactionId.value()))
					.param("processedAt", Timestamp.from(processedAt))
					.update();
		}
		catch (DuplicateKeyException e) {
			throw new AlreadyProcessed();
		}
	}
}
