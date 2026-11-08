package com.toniflorea.ledger.domain.account;

import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import com.toniflorea.ledger.domain.transaction.Direction;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record Account(AccountId id, Currency currency, AccountKind kind, OverdraftLimit overdraftLimit, Money balance,
		Instant openedAt) {

	public Account {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(currency, "currency");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(overdraftLimit, "overdraftLimit");
		Objects.requireNonNull(balance, "balance");
		Objects.requireNonNull(openedAt, "openedAt");
		if (balance.currency() != currency) {
			throw new IllegalArgumentException("balance currency " + balance.currency() + " differs from " + currency);
		}
		overdraftLimit.asMoney().filter(m -> m.currency() != currency).ifPresent(m -> {
			throw new IllegalArgumentException("overdraft limit currency " + m.currency() + " differs from " + currency);
		});
	}

	public static Account open(AccountId id, Currency currency, OverdraftLimit overdraftLimit, Instant openedAt) {
		return new Account(id, currency, AccountKind.REGULAR, overdraftLimit, Money.zero(currency), openedAt);
	}

	public static Account settlement(AccountId id, Currency currency, Instant openedAt) {
		return new Account(id, currency, AccountKind.SETTLEMENT, OverdraftLimit.unlimited(), Money.zero(currency),
				openedAt);
	}

	public Optional<Money> availableBalance() {
		return overdraftLimit.available(balance);
	}

	public Money balanceAfter(Direction direction, Money amount) {
		return direction == Direction.CREDIT ? balance.plus(amount) : balance.minus(amount);
	}

	public boolean allows(Money newBalance) {
		return overdraftLimit.allows(newBalance);
	}

	public Account withBalance(Money newBalance) {
		return new Account(id, currency, kind, overdraftLimit, newBalance, openedAt);
	}
}
