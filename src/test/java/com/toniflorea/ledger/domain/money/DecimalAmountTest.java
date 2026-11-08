package com.toniflorea.ledger.domain.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DecimalAmountTest {

	@Test
	void zeroInAnyNotationIsZeroAndNotPositive() {
		assertThat(DecimalAmount.parse("0", null).isZero()).isTrue();
		assertThat(DecimalAmount.parse("0.00", null).isZero()).isTrue();
		assertThat(DecimalAmount.parse("0.00", null).isPositive()).isFalse();
		assertThat(DecimalAmount.parse("-0", null).isPositive()).isFalse();
	}

	@Test
	void negativeIsNotPositive() {
		assertThat(DecimalAmount.parse("-1", null).isPositive()).isFalse();
		assertThat(DecimalAmount.parse("0.01", null).isPositive()).isTrue();
	}

	@Test
	void keepsTheOriginalScaleAndText() {
		DecimalAmount amount = DecimalAmount.parse("100.50", null);

		assertThat(amount.scale()).isEqualTo(2);
		assertThat(amount).hasToString("100.50");
		assertThat(DecimalAmount.parse("7", null)).hasToString("7");
	}
}
