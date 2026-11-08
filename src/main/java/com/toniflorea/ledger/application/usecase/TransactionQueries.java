package com.toniflorea.ledger.application.usecase;

import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.util.Optional;

public interface TransactionQueries {

	Details get(TransactionId id);

	record Details(Transaction transaction, Optional<TransactionId> reversedBy) {
	}
}
