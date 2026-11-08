package com.toniflorea.ledger.application.usecase;

import com.toniflorea.ledger.domain.transaction.TransactionId;

public interface RecordSettledPayment {

	Outcome record(Command command);

	record Command(String eventId, String paymentId, String merchantAccountId, String amount, String currency) {
	}

	record Outcome(TransactionId transactionId, boolean duplicate) {
	}
}
