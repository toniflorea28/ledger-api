package com.toniflorea.ledger.application.usecase;

import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.Transaction;
import java.util.List;

public interface RecordTransaction {

	Transaction record(Command command);

	record Command(String description, List<Entry> entries) {
	}

	record Entry(AccountId accountId, Direction direction, String amount) {
	}
}
