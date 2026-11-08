package com.toniflorea.ledger.application.port;

import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EntryQueryRepository {

	record AccountEntry(long id, TransactionId transactionId, String description, Direction direction, Money amount,
			Money balanceAfter, Instant recordedAt) {
	}

	List<AccountEntry> findNewestFirst(AccountId accountId, Currency currency, Optional<Long> beforeId, int limit);

	Optional<Money> balanceAt(AccountId accountId, Currency currency, Instant at);
}
