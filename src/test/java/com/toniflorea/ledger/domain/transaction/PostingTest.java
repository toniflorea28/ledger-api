package com.toniflorea.ledger.domain.transaction;

import static com.toniflorea.ledger.domain.Fixtures.apply;
import static com.toniflorea.ledger.domain.Fixtures.balance;
import static com.toniflorea.ledger.domain.Fixtures.credit;
import static com.toniflorea.ledger.domain.Fixtures.debit;
import static com.toniflorea.ledger.domain.Fixtures.id;
import static com.toniflorea.ledger.domain.Fixtures.ledger;
import static com.toniflorea.ledger.domain.Fixtures.post;
import static com.toniflorea.ledger.domain.Fixtures.regular;
import static com.toniflorea.ledger.domain.Fixtures.settlement;
import static com.toniflorea.ledger.domain.Fixtures.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerError;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowingConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PostingTest {

	Account settlementEur = settlement(Currency.EUR);
	Account settlementJpy = settlement(Currency.JPY);
	Account merchant42 = regular("Merchant 42", Currency.EUR);
	Account payoutsEur = regular("Payouts EUR", Currency.EUR);
	Account feesEur = regular("Fees EUR", Currency.EUR);
	Account merchant7 = regular("Merchant 7", Currency.JPY);
	Account payoutsJpy = regular("Payouts JPY", Currency.JPY);

	Map<AccountId, Account> accounts;

	@BeforeEach
	void allAccountsStartAtZero() {
		accounts = ledger(settlementEur, settlementJpy, merchant42, payoutsEur, feesEur, merchant7, payoutsJpy);
	}

	@Test
	void example1_aPaymentSettlesForMerchant42() {
		Posting.Result result = post(accounts, "payment settled",
				debit(settlementEur, "125.50"), credit(merchant42, "125.50"));
		accounts = apply(accounts, result);

		assertThat(balance(accounts, settlementEur)).isEqualTo("-125.50");
		assertThat(balance(accounts, merchant42)).isEqualTo("125.50");
		assertThat(result.transaction().currency()).isEqualTo(Currency.EUR);
		assertThat(result.transaction().entries())
				.extracting(e -> e.accountId(), e -> e.direction(), e -> e.balanceAfter().toDecimalString())
				.containsExactly(
						tuple(settlementEur.id(), Direction.DEBIT, "-125.50"),
						tuple(merchant42.id(), Direction.CREDIT, "125.50"));
		assertThat(sum(accounts, Currency.EUR).isZero()).isTrue();
	}

	@Test
	void example2_merchant42IsPaidOutWithAFee() {
		accounts = apply(accounts, post(accounts, "payment settled",
				debit(settlementEur, "125.50"), credit(merchant42, "125.50")));

		accounts = apply(accounts, post(accounts, "payout",
				debit(merchant42, "100.00"), credit(payoutsEur, "98.50"), credit(feesEur, "1.50")));

		assertThat(balance(accounts, settlementEur)).isEqualTo("-125.50");
		assertThat(balance(accounts, merchant42)).isEqualTo("25.50");
		assertThat(balance(accounts, payoutsEur)).isEqualTo("98.50");
		assertThat(balance(accounts, feesEur)).isEqualTo("1.50");
		assertThat(sum(accounts, Currency.EUR).isZero()).isTrue();
	}

	@Test
	void example4_aJpyPaymentSettlesWithoutDecimals() {
		accounts = apply(accounts, post(accounts, "payment settled",
				debit(settlementJpy, "15000"), credit(merchant7, "15000")));

		assertThat(balance(accounts, settlementJpy)).isEqualTo("-15000");
		assertThat(balance(accounts, merchant7)).isEqualTo("15000");
		assertThat(sum(accounts, Currency.JPY).isZero()).isTrue();
	}

	@Nested
	class Example5_refusedStartingFromTheBalancesAfterExample2 {

		@BeforeEach
		void reachExample2() {
			accounts = apply(accounts, post(accounts, "payment settled",
					debit(settlementEur, "125.50"), credit(merchant42, "125.50")));
			accounts = apply(accounts, post(accounts, "payout",
					debit(merchant42, "100.00"), credit(payoutsEur, "98.50"), credit(feesEur, "1.50")));
		}

		@Test
		void debitsAndCreditsThatDoNotBalance() {
			assertRefused(() -> post(accounts, "x", debit(merchant42, "100.00"), credit(payoutsEur, "99.00")),
					List.of(error(ErrorCode.UNBALANCED, "/entries")),
					e -> assertThat(e.errors().get(0).detail()).contains("100.00 EUR").contains("99.00 EUR"));
		}

		@Test
		void aDebitPastTheOverdraftLimit() {
			assertRefused(() -> post(accounts, "x", debit(merchant42, "50.00"), credit(payoutsEur, "50.00")),
					List.of(error(ErrorCode.OVERDRAFT_EXCEEDED, "/entries/0/accountId")),
					e -> assertThat(e.errors().get(0).detail()).contains("25.50").contains("-24.50").contains("0.00"));
		}

		@Test
		void decimalsOnAJpyAccount() {
			assertRefused(() -> post(accounts, "x", debit(merchant7, "100.5"), credit(payoutsJpy, "100.5")),
					List.of(error(ErrorCode.SCALE_EXCEEDED, "/entries/0/amount"),
							error(ErrorCode.SCALE_EXCEEDED, "/entries/1/amount")),
					e -> assertThat(e.errors().get(0).detail()).contains("JPY allows 0"));
		}

		@Test
		void accountsInDifferentCurrencies() {
			assertRefused(() -> post(accounts, "x", debit(merchant42, "10.00"), credit(merchant7, "10")),
					List.of(error(ErrorCode.CURRENCY_MISMATCH, "/entries/1/accountId")),
					e -> assertThat(e.errors().get(0).detail()).contains("JPY").contains("EUR"));
		}

		@Test
		void oneEntryOnly() {
			assertRefused(() -> post(accounts, "x", credit(merchant42, "10.00")),
					List.of(error(ErrorCode.TOO_FEW_ENTRIES, "/entries")),
					e -> assertThat(e.errors().get(0).detail()).contains("got 1"));
		}

		@Test
		void anEntryPostingToAnAccountThatDoesNotExist() {
			AccountId ghost = id("nobody");

			assertRefused(() -> post(accounts, "x", debit(merchant42, "10.00"), credit(ghost, "10.00")),
					List.of(error(ErrorCode.UNKNOWN_ACCOUNT, "/entries/1/accountId")),
					e -> assertThat(e.errors().get(0).detail()).contains(ghost.toString()));
		}

		@Test
		void nothingChangesWhenRefused() {
			Map<AccountId, Account> before = Map.copyOf(accounts);

			assertThatThrownBy(() -> post(accounts, "x", debit(merchant42, "50.00"), credit(payoutsEur, "50.00")))
					.isInstanceOf(LedgerException.class);

			assertThat(accounts).isEqualTo(before);
		}
	}

	@Test
	void aZeroOrNegativeAmountIsRefused() {
		assertRefused(() -> post(accounts, "x", debit(settlementEur, "0.00"), credit(merchant42, "0.00")),
				List.of(error(ErrorCode.NON_POSITIVE_AMOUNT, "/entries/0/amount"),
						error(ErrorCode.NON_POSITIVE_AMOUNT, "/entries/1/amount")),
				e -> assertThat(e.errors().get(0).detail()).contains("0.00"));
		assertRefused(() -> post(accounts, "x", debit(settlementEur, "-5.00"), credit(merchant42, "5.00")),
				List.of(error(ErrorCode.NON_POSITIVE_AMOUNT, "/entries/0/amount")), e -> {
				});
	}

	@Test
	void allIndependentProblemsAreReportedTogether() {
		AccountId ghost = id("nobody");

		assertRefused(() -> post(accounts, "x", debit(ghost, "10.00"), credit(merchant7, "0.5"),
				credit(merchant42, "-1")),
				List.of(error(ErrorCode.UNKNOWN_ACCOUNT, "/entries/0/accountId"),
						error(ErrorCode.SCALE_EXCEEDED, "/entries/1/amount"),
						error(ErrorCode.NON_POSITIVE_AMOUNT, "/entries/2/amount"),
						error(ErrorCode.CURRENCY_MISMATCH, "/entries/2/accountId")),
				e -> {
				});
	}

	@Test
	void anAccountMayAppearInSeveralEntriesAndBalanceAfterAccumulates() {
		Posting.Result result = post(accounts, "split",
				debit(settlementEur, "10.00"), credit(merchant42, "4.00"), credit(merchant42, "6.00"));

		assertThat(result.transaction().entries()).extracting(e -> e.balanceAfter().toDecimalString())
				.containsExactly("-10.00", "4.00", "10.00");
		assertThat(result.accounts()).extracting(Account::id).containsExactly(settlementEur.id(), merchant42.id());
	}

	@Test
	void anOverdraftLimitLetsTheBalanceGoBelowZeroUpToTheLimit() {
		Account withLimit = regular("Merchant with limit", Currency.EUR, "30.00");
		accounts.put(withLimit.id(), withLimit);
		accounts = apply(accounts, post(accounts, "settle", debit(settlementEur, "5.50"), credit(withLimit, "5.50")));

		accounts = apply(accounts, post(accounts, "payout", debit(withLimit, "30.00"), credit(payoutsEur, "30.00")));
		assertThat(balance(accounts, withLimit)).isEqualTo("-24.50");

		assertRefused(() -> post(accounts, "x", debit(withLimit, "5.51"), credit(payoutsEur, "5.51")),
				List.of(error(ErrorCode.OVERDRAFT_EXCEEDED, "/entries/0/accountId")),
				e -> assertThat(e.errors().get(0).detail()).contains("-30.01").contains("30.00"));
	}

	@Test
	void anOverdraftViolationIsReportedOncePerAccount() {
		assertRefused(() -> post(accounts, "x", debit(merchant42, "1.00"), debit(merchant42, "1.00"),
				credit(payoutsEur, "2.00")),
				List.of(error(ErrorCode.OVERDRAFT_EXCEEDED, "/entries/0/accountId")),
				e -> assertThat(e.errors().get(0).detail()).contains("-2.00"));
	}

	@Test
	void theRecordedTransactionCarriesWhatWasAsked() {
		Posting.Result result = post(accounts, "payment settled",
				debit(settlementEur, "125.50"), credit(merchant42, "125.50"));

		Transaction tx = result.transaction();
		assertThat(tx.description()).isEqualTo("payment settled");
		assertThat(tx.isReversal()).isFalse();
		assertThat(tx.reverses()).isNull();
		assertThat(tx.entries()).extracting(Entry::amount)
				.containsOnly(Money.parse("125.50", Currency.EUR, null));
	}

	private static LedgerError error(ErrorCode code, String pointer) {
		return LedgerError.of(code, pointer, "");
	}

	private static void assertRefused(Runnable posting, List<LedgerError> expected,
			ThrowingConsumer<LedgerException> more) {
		assertThatThrownBy(posting::run).isInstanceOfSatisfying(LedgerException.class, e -> {
			assertThat(e.errors()).extracting(LedgerError::code, LedgerError::pointer)
					.containsExactlyElementsOf(expected.stream().map(x -> tuple(x.code(), x.pointer())).toList());
			more.accept(e);
		});
	}
}
