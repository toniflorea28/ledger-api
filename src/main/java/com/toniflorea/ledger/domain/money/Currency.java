package com.toniflorea.ledger.domain.money;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import java.util.Arrays;
import java.util.regex.Pattern;

public enum Currency {
	EUR(2), USD(2), JPY(0);

	private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

	private final int scale;

	Currency(int scale) {
		this.scale = scale;
	}

	public int scale() {
		return scale;
	}

	public static Currency parse(String code, String pointer) {
		if (code == null || !CODE.matcher(code).matches() || !isIso4217(code)) {
			throw LedgerException.of(ErrorCode.INVALID_CURRENCY_CODE, pointer,
					"'" + code + "' is not an ISO 4217 currency code");
		}
		return Arrays.stream(values())
				.filter(c -> c.name().equals(code))
				.findFirst()
				.orElseThrow(() -> LedgerException.of(ErrorCode.UNSUPPORTED_CURRENCY, pointer,
						code + " is an ISO 4217 code but not a currency the ledger supports (EUR, USD, JPY)"));
	}

	private static boolean isIso4217(String code) {
		try {
			java.util.Currency.getInstance(code);
			return true;
		}
		catch (IllegalArgumentException e) {
			return false;
		}
	}
}
