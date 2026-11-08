package com.toniflorea.ledger.domain.transaction;

import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.Money;
import java.util.Objects;

public record Entry(AccountId accountId, Direction direction, Money amount, Money balanceAfter) {

	public Entry {
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(direction, "direction");
		Objects.requireNonNull(amount, "amount");
		Objects.requireNonNull(balanceAfter, "balanceAfter");
	}
}
