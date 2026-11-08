package com.toniflorea.ledger.domain.transaction;

import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerError;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Posting {

	public record Result(Transaction transaction, List<Account> accounts) {
	}

	private Posting() {
	}

	public static Result post(TransactionDraft draft, TransactionId id, Instant recordedAt,
			Map<AccountId, Account> accounts) {
		List<LedgerError> errors = new ArrayList<>();
		List<EntryDraft> drafts = draft.entries();

		if (drafts.size() < 2) {
			errors.add(LedgerError.of(ErrorCode.TOO_FEW_ENTRIES, "/entries",
					"a transaction needs at least two entries, got " + drafts.size()));
		}

		Currency currency = null;
		Money[] amounts = new Money[drafts.size()];
		for (int i = 0; i < drafts.size(); i++) {
			EntryDraft entry = drafts.get(i);
			String base = "/entries/" + i;
			if (!entry.amount().isPositive()) {
				errors.add(LedgerError.of(ErrorCode.NON_POSITIVE_AMOUNT, base + "/amount",
						"amount must be positive, got " + entry.amount()));
			}
			Account account = accounts.get(entry.accountId());
			if (account == null) {
				errors.add(LedgerError.of(ErrorCode.UNKNOWN_ACCOUNT, base + "/accountId",
						"account " + entry.accountId() + " does not exist"));
				continue;
			}
			if (currency == null) {
				currency = account.currency();
			}
			else if (account.currency() != currency) {
				errors.add(LedgerError.of(ErrorCode.CURRENCY_MISMATCH, base + "/accountId",
						"account " + entry.accountId() + " is in " + account.currency() + " but the transaction is in "
								+ currency + " (the currency of the account in entry 0)"));
				continue;
			}
			try {
				amounts[i] = entry.amount().toMoney(account.currency(), base + "/amount");
			}
			catch (LedgerException e) {
				errors.addAll(e.errors());
			}
		}
		if (!errors.isEmpty()) {
			throw new LedgerException(errors);
		}

		Money debits = Money.zero(currency);
		Money credits = Money.zero(currency);
		for (int i = 0; i < drafts.size(); i++) {
			if (drafts.get(i).direction() == Direction.DEBIT) {
				debits = debits.plus(amounts[i]);
			}
			else {
				credits = credits.plus(amounts[i]);
			}
		}
		if (!debits.equals(credits)) {
			throw LedgerException.of(ErrorCode.UNBALANCED, "/entries",
					"debits total " + debits + " but credits total " + credits);
		}

		Map<AccountId, Account> updated = new LinkedHashMap<>();
		List<Entry> entries = new ArrayList<>(drafts.size());
		for (int i = 0; i < drafts.size(); i++) {
			EntryDraft entry = drafts.get(i);
			Account account = updated.getOrDefault(entry.accountId(), accounts.get(entry.accountId()));
			Money balanceAfter = account.balanceAfter(entry.direction(), amounts[i]);
			updated.put(entry.accountId(), account.withBalance(balanceAfter));
			entries.add(new Entry(entry.accountId(), entry.direction(), amounts[i], balanceAfter));
		}
		Set<AccountId> checked = new HashSet<>();
		for (int i = 0; i < drafts.size(); i++) {
			AccountId accountId = drafts.get(i).accountId();
			if (!checked.add(accountId)) {
				continue;
			}
			Account after = updated.get(accountId);
			if (!after.allows(after.balance())) {
				Account before = accounts.get(accountId);
				errors.add(LedgerError.of(ErrorCode.OVERDRAFT_EXCEEDED, "/entries/" + i + "/accountId",
						"account " + accountId + " would go from " + before.balance().toDecimalString() + " to "
								+ after.balance().toDecimalString() + ", past its overdraft limit of "
								+ after.overdraftLimit().asMoney().map(Money::toDecimalString).orElse("none")));
			}
		}
		if (!errors.isEmpty()) {
			throw new LedgerException(errors);
		}

		Transaction transaction = new Transaction(id, draft.description(), currency, recordedAt, entries,
				draft.reverses());
		return new Result(transaction, List.copyOf(updated.values()));
	}
}
