package com.toniflorea.ledger.domain.money;

import java.util.Objects;

public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

	public Money {
		Objects.requireNonNull(currency, "currency");
	}

	public static Money ofMinor(long minorUnits, Currency currency) {
		return new Money(minorUnits, currency);
	}

	public static Money zero(Currency currency) {
		return new Money(0, currency);
	}

	public static Money parse(String text, Currency currency, String pointer) {
		return DecimalAmount.parse(text, pointer).toMoney(currency, pointer);
	}

	public Money plus(Money other) {
		return new Money(Math.addExact(minorUnits, sameCurrency(other).minorUnits), currency);
	}

	public Money minus(Money other) {
		return new Money(Math.subtractExact(minorUnits, sameCurrency(other).minorUnits), currency);
	}

	public Money negate() {
		return new Money(Math.negateExact(minorUnits), currency);
	}

	public boolean isNegative() {
		return minorUnits < 0;
	}

	public boolean isPositive() {
		return minorUnits > 0;
	}

	public boolean isZero() {
		return minorUnits == 0;
	}

	@Override
	public int compareTo(Money other) {
		return Long.compare(minorUnits, sameCurrency(other).minorUnits);
	}

	public String toDecimalString() {
		int scale = currency.scale();
		long abs = Math.abs(minorUnits);
		String digits = Long.toString(abs);
		if (scale == 0) {
			return (minorUnits < 0 ? "-" : "") + digits;
		}
		if (digits.length() <= scale) {
			digits = "0".repeat(scale - digits.length() + 1) + digits;
		}
		int split = digits.length() - scale;
		return (minorUnits < 0 ? "-" : "") + digits.substring(0, split) + "." + digits.substring(split);
	}

	@Override
	public String toString() {
		return toDecimalString() + " " + currency;
	}

	private Money sameCurrency(Money other) {
		if (other.currency != currency) {
			throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
		}
		return other;
	}
}
