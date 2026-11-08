package com.toniflorea.ledger.application.port;

import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.time.Instant;
import java.util.Optional;

public interface ProcessedPaymentStore {

	class AlreadyProcessed extends RuntimeException {
	}

	Optional<TransactionId> find(String paymentId);

	void insert(String paymentId, String eventId, TransactionId transactionId, Instant processedAt);
}
