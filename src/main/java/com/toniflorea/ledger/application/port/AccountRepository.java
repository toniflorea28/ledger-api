package com.toniflorea.ledger.application.port;

import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;

public interface AccountRepository {

	Optional<Account> find(AccountId id);

	Map<AccountId, Account> lockAll(SortedSet<AccountId> ids);

	void insert(Account account);

	void updateBalance(Account account);

	List<Account> findSettlementAccounts();

	Optional<Account> findSettlementAccount(Currency currency);
}
