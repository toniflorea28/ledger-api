package com.toniflorea.ledger.application.service;

import com.toniflorea.ledger.application.port.AccountRepository;
import com.toniflorea.ledger.application.port.EntryQueryRepository;
import com.toniflorea.ledger.application.port.EntryQueryRepository.AccountEntry;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class AccountService implements OpenAccount, AccountQueries {

	private final AccountRepository accounts;
	private final EntryQueryRepository entries;
	private final IdGenerator ids;
	private final LedgerClock clock;

	public AccountService(AccountRepository accounts, EntryQueryRepository entries, IdGenerator ids,
			LedgerClock clock) {
		this.accounts = accounts;
		this.entries = entries;
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

	@Override
	@Transactional(readOnly = true)
	public EntriesPage entries(AccountId id, Optional<String> cursor, int limit) {
		Account account = get(id);
		Optional<Long> before = cursor.map(AccountService::parseCursor);
		List<AccountEntry> page = entries.findNewestFirst(account.id(), account.currency(), before, limit + 1);
		if (page.size() <= limit) {
			return new EntriesPage(page, Optional.empty());
		}
		List<AccountEntry> items = page.subList(0, limit);
		return new EntriesPage(items, Optional.of(Long.toString(items.get(limit - 1).id())));
	}

	@Override
	@Transactional(readOnly = true)
	public Money balanceAt(AccountId id, Instant at) {
		Account account = get(id);
		return entries.balanceAt(account.id(), account.currency(), at).orElse(Money.zero(account.currency()));
	}

	private static long parseCursor(String cursor) {
		try {
			return Long.parseLong(cursor);
		}
		catch (NumberFormatException e) {
			throw LedgerException.of(ErrorCode.MALFORMED_REQUEST, "cursor", "'" + cursor + "' is not a cursor from a previous page");
		}
	}
}
