package com.toniflorea.ledger.domain.account;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import java.util.Optional;

public record OverdraftLimit(Money amount) {

	private static final OverdraftLimit UNLIMITED = new OverdraftLimit(null);

	public static OverdraftLimit unlimited() {
		return UNLIMITED;
	}

	public static OverdraftLimit zero(Currency currency) {
		return new OverdraftLimit(Money.zero(currency));
	}

	public static OverdraftLimit of(Money amount, String pointer) {
		if (amount.isNegative()) {
			throw LedgerException.of(ErrorCode.INVALID_AMOUNT, pointer,
					"overdraft limit must not be negative, got " + amount.toDecimalString());
		}
		return new OverdraftLimit(amount);
	}

	public boolean isUnlimited() {
		return amount == null;
	}

	public Optional<Money> asMoney() {
		return Optional.ofNullable(amount);
	}

	public boolean allows(Money balance) {
		return isUnlimited() || !balance.plus(amount).isNegative();
	}

	public Optional<Money> available(Money balance) {
		return asMoney().map(balance::plus);
	}
}
