package com.toniflorea.ledger.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

	@Test
	void tenCentsPlusTwentyCentsIsExactlyThirtyCents() {
		Money sum = Money.parse("0.10", Currency.EUR, null).plus(Money.parse("0.20", Currency.EUR, null));

		assertThat(sum).isEqualTo(Money.parse("0.30", Currency.EUR, null));
		assertThat(sum.minorUnits()).isEqualTo(30);
		assertThat(sum.toDecimalString()).isEqualTo("0.30");
	}

	@ParameterizedTest
	@CsvSource({
			"125.50, EUR, 12550, 125.50",
			"100, EUR, 10000, 100.00",
			"0.05, USD, 5, 0.05",
			"0, EUR, 0, 0.00",
			"15000, JPY, 15000, 15000",
			"-24.50, EUR, -2450, -24.50",
			"-0.05, EUR, -5, -0.05",
			"-15000, JPY, -15000, -15000"
	})
	void parsesAndFormatsWithoutLoss(String text, Currency currency, long minor, String formatted) {
		Money money = Money.parse(text, currency, "/amount");

		assertThat(money.minorUnits()).isEqualTo(minor);
		assertThat(money.toDecimalString()).isEqualTo(formatted);
	}

	@ParameterizedTest
	@CsvSource({ "100.5, JPY", "100.500, EUR", "0.001, USD" })
	void refusesMoreDecimalPlacesThanTheCurrencyAllows(String text, Currency currency) {
		assertThatThrownBy(() -> Money.parse(text, currency, "/entries/0/amount"))
				.isInstanceOfSatisfying(LedgerException.class, e -> {
					assertThat(e.errors()).singleElement().satisfies(err -> {
						assertThat(err.code()).isEqualTo(ErrorCode.SCALE_EXCEEDED);
						assertThat(err.pointer()).isEqualTo("/entries/0/amount");
						assertThat(err.detail()).contains(currency.name());
					});
				});
	}

	@ParameterizedTest
	@ValueSource(strings = { "15,000", "1e3", "12.", ".5", "abc", "", "1.2.3", "+5", "1 000" })
	void refusesTextThatIsNotADecimalAmount(String text) {
		assertThatThrownBy(() -> Money.parse(text, Currency.EUR, "/amount"))
				.isInstanceOfSatisfying(LedgerException.class,
						e -> assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.INVALID_AMOUNT));
	}

	@Test
	void arithmeticAcrossCurrenciesIsAProgrammingError() {
		Money eur = Money.ofMinor(100, Currency.EUR);
		Money jpy = Money.ofMinor(100, Currency.JPY);

		assertThatThrownBy(() -> eur.plus(jpy)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> eur.compareTo(jpy)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void overflowIsAnErrorNotAWrapAround() {
		Money max = Money.ofMinor(Long.MAX_VALUE, Currency.EUR);

		assertThatThrownBy(() -> max.plus(Money.ofMinor(1, Currency.EUR))).isInstanceOf(ArithmeticException.class);
	}

	@Test
	void signHelpers() {
		assertThat(Money.ofMinor(-1, Currency.EUR).isNegative()).isTrue();
		assertThat(Money.ofMinor(0, Currency.EUR).isZero()).isTrue();
		assertThat(Money.ofMinor(1, Currency.EUR).isPositive()).isTrue();
		assertThat(Money.ofMinor(5, Currency.EUR).negate()).isEqualTo(Money.ofMinor(-5, Currency.EUR));
		assertThat(Money.ofMinor(5, Currency.EUR).minus(Money.ofMinor(7, Currency.EUR)))
				.isEqualTo(Money.ofMinor(-2, Currency.EUR));
	}
}
