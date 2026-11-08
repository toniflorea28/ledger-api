package com.toniflorea.ledger.application.service;

import com.toniflorea.ledger.application.port.AccountRepository;
import com.toniflorea.ledger.application.port.IdGenerator;
import com.toniflorea.ledger.application.port.LedgerClock;
import com.toniflorea.ledger.application.usecase.AccountQueries;
import com.toniflorea.ledger.application.usecase.OpenAccount;
import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.account.OverdraftLimit;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class AccountService implements OpenAccount, AccountQueries {

	private final AccountRepository accounts;
	private final IdGenerator ids;
	private final LedgerClock clock;

	public AccountService(AccountRepository accounts, IdGenerator ids, LedgerClock clock) {
		this.accounts = accounts;
		this.ids = ids;
		this.clock = clock;
	}

	@Override
	@Transactional
	public Account open(Command command) {
		Currency currency = Currency.parse(command.currencyCode(), "/currency");
		OverdraftLimit limit = command.overdraftLimit()
				.map(text -> OverdraftLimit.of(Money.parse(text, currency, "/overdraftLimit"), "/overdraftLimit"))
				.orElse(OverdraftLimit.zero(currency));
		Account account = Account.open(AccountId.of(ids.next()), currency, limit, clock.now());
		accounts.insert(account);
		return account;
	}

	@Override
	@Transactional(readOnly = true)
	public Account get(AccountId id) {
		return accounts.find(id)
				.orElseThrow(() -> LedgerException.of(ErrorCode.NOT_FOUND, "account " + id + " does not exist"));
	}

	@Override
	@Transactional(readOnly = true)
	public List<Account> settlementAccounts() {
		return accounts.findSettlementAccounts();
	}
}
