package com.toniflorea.ledger.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyTest {

	@Test
	void supportedCurrenciesHaveTheirIso4217Scale() {
		assertThat(Currency.parse("EUR", null).scale()).isEqualTo(2);
		assertThat(Currency.parse("USD", null).scale()).isEqualTo(2);
		assertThat(Currency.parse("JPY", null).scale()).isEqualTo(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "EURO", "ABC", "eur", "EU", "" })
	void refusesCodesThatAreNotIso4217(String code) {
		assertThatThrownBy(() -> Currency.parse(code, "/currency"))
				.isInstanceOfSatisfying(LedgerException.class, e -> {
					assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.INVALID_CURRENCY_CODE);
					assertThat(e.errors().get(0).pointer()).isEqualTo("/currency");
				});
	}

	@ParameterizedTest
	@ValueSource(strings = { "GBP", "CHF", "RON" })
	void refusesValidIso4217CodesTheLedgerDoesNotSupport(String code) {
		assertThatThrownBy(() -> Currency.parse(code, "/currency"))
				.isInstanceOfSatisfying(LedgerException.class, e -> {
					assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.UNSUPPORTED_CURRENCY);
					assertThat(e.errors().get(0).detail()).contains(code).contains("EUR, USD, JPY");
				});
	}

	@Test
	void nullIsNotACurrencyCode() {
		assertThatThrownBy(() -> Currency.parse(null, "/currency"))
				.isInstanceOfSatisfying(LedgerException.class,
						e -> assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.INVALID_CURRENCY_CODE));
	}
}
