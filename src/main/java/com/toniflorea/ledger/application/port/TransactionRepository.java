package com.toniflorea.ledger.application.port;

import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.util.Optional;

public interface TransactionRepository {

	void insert(Transaction transaction);

	Optional<Transaction> find(TransactionId id);

	Optional<Transaction> lock(TransactionId id);

	Optional<TransactionId> findReversalOf(TransactionId id);
}
