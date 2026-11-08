package com.toniflorea.ledger.domain.transaction;

import static com.toniflorea.ledger.domain.Fixtures.apply;
import static com.toniflorea.ledger.domain.Fixtures.balance;
import static com.toniflorea.ledger.domain.Fixtures.credit;
import static com.toniflorea.ledger.domain.Fixtures.debit;
import static com.toniflorea.ledger.domain.Fixtures.ledger;
import static com.toniflorea.ledger.domain.Fixtures.post;
import static com.toniflorea.ledger.domain.Fixtures.regular;
import static com.toniflorea.ledger.domain.Fixtures.settlement;
import static com.toniflorea.ledger.domain.Fixtures.sum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.Currency;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReversalTest {

	Account settlementEur = settlement(Currency.EUR);
	Account merchant42 = regular("Merchant 42", Currency.EUR);
	Account payoutsEur = regular("Payouts EUR", Currency.EUR);
	Account feesEur = regular("Fees EUR", Currency.EUR);

	Map<AccountId, Account> accounts;
	Transaction payout;

	@BeforeEach
	void reachExample2() {
		accounts = ledger(settlementEur, merchant42, payoutsEur, feesEur);
		accounts = apply(accounts, post(accounts, "payment settled",
				debit(settlementEur, "125.50"), credit(merchant42, "125.50")));
		Posting.Result result = post(accounts, "payout",
				debit(merchant42, "100.00"), credit(payoutsEur, "98.50"), credit(feesEur, "1.50"));
		accounts = apply(accounts, result);
		payout = result.transaction();
	}

	@Test
	void example3_thePayoutIsReversedByANewLinkedTransactionWithEveryEntryFlipped() {
		TransactionDraft draft = Reversal.draft(payout, Optional.empty(), Optional.empty());
		Posting.Result result = post(accounts, draft);
		accounts = apply(accounts, result);

		Transaction reversal = result.transaction();
		assertThat(reversal.isReversal()).isTrue();
		assertThat(reversal.reverses()).isEqualTo(payout.id());
		assertThat(reversal.id()).isNotEqualTo(payout.id());
		assertThat(reversal.description()).isEqualTo("Reversal of " + payout.id());
		assertThat(reversal.entries()).extracting(Entry::accountId, Entry::direction, e -> e.amount().toDecimalString())
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(merchant42.id(), Direction.CREDIT, "100.00"),
						org.assertj.core.groups.Tuple.tuple(payoutsEur.id(), Direction.DEBIT, "98.50"),
						org.assertj.core.groups.Tuple.tuple(feesEur.id(), Direction.DEBIT, "1.50"));

		assertThat(balance(accounts, merchant42)).isEqualTo("125.50");
		assertThat(balance(accounts, payoutsEur)).isEqualTo("0.00");
		assertThat(balance(accounts, feesEur)).isEqualTo("0.00");
		assertThat(sum(accounts, Currency.EUR).isZero()).isTrue();
	}

	@Test
	void theDescriptionCanBeGiven() {
		TransactionDraft draft = Reversal.draft(payout, Optional.empty(), Optional.of("customer complaint"));

		assertThat(draft.description()).isEqualTo("customer complaint");
	}

	@Test
	void reversingASecondTimeIsRefused() {
		Transaction first = post(accounts, Reversal.draft(payout, Optional.empty(), Optional.empty())).transaction();

		assertThatThrownBy(() -> Reversal.draft(payout, Optional.of(first.id()), Optional.empty()))
				.isInstanceOfSatisfying(LedgerException.class, e -> {
					assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.ALREADY_REVERSED);
					assertThat(e.errors().get(0).detail()).contains(payout.id().toString()).contains(first.id().toString());
				});
	}

	@Test
	void aReversalCannotBeReversed() {
		Transaction reversal = post(accounts, Reversal.draft(payout, Optional.empty(), Optional.empty())).transaction();

		assertThatThrownBy(() -> Reversal.draft(reversal, Optional.empty(), Optional.empty()))
				.isInstanceOfSatisfying(LedgerException.class,
						e -> assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.CANNOT_REVERSE_REVERSAL));
	}

	@Test
	void aReversalObeysOverdraftLimitsLikeAnyTransaction() {
		Posting.Result spend = post(accounts, "spend the payout",
				debit(payoutsEur, "98.50"), credit(merchant42, "98.50"));
		accounts = apply(accounts, spend);

		TransactionDraft draft = Reversal.draft(payout, Optional.empty(), Optional.empty());

		assertThatThrownBy(() -> post(accounts, draft)).isInstanceOfSatisfying(LedgerException.class, e -> {
			assertThat(e.errors().get(0).code()).isEqualTo(ErrorCode.OVERDRAFT_EXCEEDED);
			assertThat(e.errors().get(0).pointer()).isEqualTo("/entries/1/accountId");
		});
	}
}
