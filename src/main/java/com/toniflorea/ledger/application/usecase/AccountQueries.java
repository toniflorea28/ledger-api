package com.toniflorea.ledger.application.usecase;

import com.toniflorea.ledger.application.port.EntryQueryRepository.AccountEntry;
import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccountQueries {

	Account get(AccountId id);

	List<Account> settlementAccounts();

	EntriesPage entries(AccountId id, Optional<String> cursor, int limit);

	Money balanceAt(AccountId id, Instant at);

	record EntriesPage(List<AccountEntry> items, Optional<String> nextCursor) {
	}
}
