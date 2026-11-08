package com.toniflorea.ledger.domain.transaction;

import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.DecimalAmount;
import java.util.Objects;

public record EntryDraft(AccountId accountId, Direction direction, DecimalAmount amount) {

	public EntryDraft {
		Objects.requireNonNull(accountId, "accountId");
		Objects.requireNonNull(direction, "direction");
		Objects.requireNonNull(amount, "amount");
	}
}
