package com.toniflorea.ledger.domain.money;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record DecimalAmount(boolean negative, String integerDigits, String fractionDigits) {

	private static final Pattern DECIMAL = Pattern.compile("(-)?([0-9]{1,15})(?:\\.([0-9]{1,6}))?");

	public static DecimalAmount parse(String text, String pointer) {
		Matcher m = text == null ? null : DECIMAL.matcher(text);
		if (m == null || !m.matches()) {
			throw LedgerException.of(ErrorCode.INVALID_AMOUNT, pointer,
					"'" + text + "' is not a decimal amount (digits with an optional fraction, e.g. 125.50)");
		}
		return new DecimalAmount(m.group(1) != null, m.group(2), m.group(3) == null ? "" : m.group(3));
	}

	public int scale() {
		return fractionDigits.length();
	}

	public boolean isZero() {
		return (integerDigits + fractionDigits).chars().allMatch(c -> c == '0');
	}

	public boolean isPositive() {
		return !negative && !isZero();
	}

	public Money toMoney(Currency currency, String pointer) {
		if (scale() > currency.scale()) {
			throw LedgerException.of(ErrorCode.SCALE_EXCEEDED, pointer,
					this + " has " + scale() + " decimal place" + (scale() == 1 ? "" : "s") + "; "
							+ currency + " allows " + currency.scale());
		}
		String padded = fractionDigits + "0".repeat(currency.scale() - scale());
		long minor = Long.parseLong(integerDigits + padded);
		return Money.ofMinor(negative ? -minor : minor, currency);
	}

	@Override
	public String toString() {
		return (negative ? "-" : "") + integerDigits + (fractionDigits.isEmpty() ? "" : "." + fractionDigits);
	}
}
