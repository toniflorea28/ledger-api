package com.toniflorea.ledger.domain;

import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.account.OverdraftLimit;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.DecimalAmount;
import com.toniflorea.ledger.domain.money.Money;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.EntryDraft;
import com.toniflorea.ledger.domain.transaction.Posting;
import com.toniflorea.ledger.domain.transaction.TransactionDraft;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Fixtures {

	public static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private Fixtures() {
	}

	public static AccountId id(String name) {
		return AccountId.of(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)));
	}

	public static Account settlement(Currency currency) {
		return Account.settlement(id("Settlement " + currency), currency, T0);
	}

	public static Account regular(String name, Currency currency) {
		return Account.open(id(name), currency, OverdraftLimit.zero(currency), T0);
	}

	public static Account regular(String name, Currency currency, String overdraftLimit) {
		return Account.open(id(name), currency, OverdraftLimit.of(Money.parse(overdraftLimit, currency, null), null),
				T0);
	}

	public static EntryDraft debit(Account account, String amount) {
		return new EntryDraft(account.id(), Direction.DEBIT, DecimalAmount.parse(amount, null));
	}

	public static EntryDraft credit(Account account, String amount) {
		return new EntryDraft(account.id(), Direction.CREDIT, DecimalAmount.parse(amount, null));
	}

	public static EntryDraft debit(AccountId accountId, String amount) {
		return new EntryDraft(accountId, Direction.DEBIT, DecimalAmount.parse(amount, null));
	}

	public static EntryDraft credit(AccountId accountId, String amount) {
		return new EntryDraft(accountId, Direction.CREDIT, DecimalAmount.parse(amount, null));
	}

	public static Map<AccountId, Account> ledger(Account... accounts) {
		Map<AccountId, Account> map = new LinkedHashMap<>();
		for (Account account : accounts) {
			map.put(account.id(), account);
		}
		return map;
	}

	public static Posting.Result post(Map<AccountId, Account> ledger, String description, EntryDraft... entries) {
		return post(ledger, TransactionDraft.of(description, List.of(entries)));
	}

	public static Posting.Result post(Map<AccountId, Account> ledger, TransactionDraft draft) {
		return Posting.post(draft, TransactionId.of(UUID.randomUUID()), T0, ledger);
	}

	public static Map<AccountId, Account> apply(Map<AccountId, Account> ledger, Posting.Result result) {
		Map<AccountId, Account> next = new LinkedHashMap<>(ledger);
		result.accounts().forEach(a -> next.put(a.id(), a));
		return next;
	}

	public static String balance(Map<AccountId, Account> ledger, Account account) {
		return ledger.get(account.id()).balance().toDecimalString();
	}

	public static Money sum(Map<AccountId, Account> ledger, Currency currency) {
		return ledger.values().stream()
				.filter(a -> a.currency() == currency)
				.map(Account::balance)
				.reduce(Money.zero(currency), Money::plus);
	}
}
