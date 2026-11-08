package com.toniflorea.ledger.domain.account;

import static com.toniflorea.ledger.domain.Fixtures.T0;
import static com.toniflorea.ledger.domain.Fixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import com.toniflorea.ledger.domain.transaction.Direction;
import org.junit.jupiter.api.Test;

class AccountTest {

	@Test
	void anOpenedAccountStartsAtZeroWithTheGivenLimit() {
		Account account = Account.open(id("m"), Currency.EUR,
				OverdraftLimit.of(Money.parse("30.00", Currency.EUR, null), null), T0);

		assertThat(account.kind()).isEqualTo(AccountKind.REGULAR);
		assertThat(account.balance()).isEqualTo(Money.zero(Currency.EUR));
		assertThat(account.availableBalance()).contains(Money.parse("30.00", Currency.EUR, null));
	}

	@Test
	void settlementAccountsHaveNoLimit() {
		Account account = Account.settlement(id("s"), Currency.JPY, T0);

		assertThat(account.kind()).isEqualTo(AccountKind.SETTLEMENT);
		assertThat(account.overdraftLimit().isUnlimited()).isTrue();
		assertThat(account.availableBalance()).isEmpty();
		assertThat(account.allows(Money.ofMinor(-1_000_000_000L, Currency.JPY))).isTrue();
	}

	@Test
	void aNegativeOverdraftLimitIsRefused() {
		assertThatThrownBy(() -> OverdraftLimit.of(Money.parse("-1.00", Currency.EUR, null), "/overdraftLimit"))
				.isInstanceOfSatisfying(LedgerException.class, e -> {
					assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.INVALID_AMOUNT);
					assertThat(e.errors().get(0).pointer()).isEqualTo("/overdraftLimit");
				});
	}

	@Test
	void creditsIncreaseAndDebitsDecreaseTheBalance() {
		Account account = Account.open(id("m"), Currency.EUR, OverdraftLimit.zero(Currency.EUR), T0);
		Money ten = Money.parse("10.00", Currency.EUR, null);

		assertThat(account.balanceAfter(Direction.CREDIT, ten)).isEqualTo(ten);
		assertThat(account.balanceAfter(Direction.DEBIT, ten)).isEqualTo(ten.negate());
	}

	@Test
	void theLimitBoundsHowFarBelowZeroTheBalanceMayGo() {
		OverdraftLimit limit = OverdraftLimit.of(Money.parse("30.00", Currency.EUR, null), null);

		assertThat(limit.allows(Money.parse("-30.00", Currency.EUR, null))).isTrue();
		assertThat(limit.allows(Money.parse("-30.01", Currency.EUR, null))).isFalse();
		assertThat(OverdraftLimit.zero(Currency.EUR).allows(Money.parse("-0.01", Currency.EUR, null))).isFalse();
	}

	@Test
	void currenciesMustAgree() {
		assertThatThrownBy(() -> new Account(id("x"), Currency.EUR, AccountKind.REGULAR,
				OverdraftLimit.zero(Currency.JPY), Money.zero(Currency.EUR), T0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Account(id("x"), Currency.EUR, AccountKind.REGULAR,
				OverdraftLimit.zero(Currency.EUR), Money.zero(Currency.USD), T0))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
