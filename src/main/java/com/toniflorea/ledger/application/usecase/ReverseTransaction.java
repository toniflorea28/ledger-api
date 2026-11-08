package com.toniflorea.ledger.application.usecase;

import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.util.Optional;

public interface ReverseTransaction {

	Transaction reverse(Command command);

	record Command(TransactionId transactionId, Optional<String> description) {
	}
}
