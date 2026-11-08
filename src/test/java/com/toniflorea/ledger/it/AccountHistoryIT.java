package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static org.assertj.core.api.Assertions.assertThat;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountEntriesPageDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountEntryDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.BalanceAtDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.support.IntegrationTest;
import com.toniflorea.ledger.support.LedgerClient.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class AccountHistoryIT extends IntegrationTest {

	@Test
	void entriesComeNewestFirstInPagesEachWithTheBalanceAfterIt() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		List<TransactionDto> recorded = new ArrayList<>();
		recorded.add(record("settle 1", debit(settlement, "10.00"), credit(merchant, "10.00")));
		recorded.add(record("settle 2", debit(settlement, "20.00"), credit(merchant, "20.00")));
		recorded.add(record("payout", debit(merchant, "5.00"), credit(payouts, "5.00")));
		recorded.add(record("settle 3", debit(settlement, "1.50"), credit(merchant, "1.50")));
		recorded.add(record("payout 2", debit(merchant, "0.50"), credit(payouts, "0.50")));

		Response<AccountEntriesPageDto> first = api.entries(merchant, null, 2);
		assertThat(first.status()).isEqualTo(200);
		assertThat(first.body().getItems()).hasSize(2);
		assertThat(first.body().getNextCursor()).isNotNull();
		assertThat(first.body().getItems().get(0).getDescription()).isEqualTo("payout 2");
		assertThat(first.body().getItems().get(0).getBalanceAfter()).isEqualTo("26.00");
		assertThat(first.body().getItems().get(0).getDirection().getValue()).isEqualTo("DEBIT");
		assertThat(first.body().getItems().get(0).getTransactionId()).isEqualTo(recorded.get(4).getId());
		assertThat(first.body().getItems().get(1).getBalanceAfter()).isEqualTo("26.50");

		List<AccountEntryDto> all = new ArrayList<>(first.body().getItems());
		String cursor = first.body().getNextCursor();
		while (cursor != null) {
			AccountEntriesPageDto page = api.entries(merchant, cursor, 2).body();
			all.addAll(page.getItems());
			cursor = page.getNextCursor();
		}
		assertThat(all).extracting(AccountEntryDto::getDescription)
				.containsExactly("payout 2", "settle 3", "payout", "settle 2", "settle 1");
		assertThat(all).extracting(AccountEntryDto::getBalanceAfter)
				.containsExactly("26.00", "26.50", "25.00", "30.00", "10.00");
		assertThat(all).extracting(AccountEntryDto::getAmount).containsExactly("0.50", "1.50", "5.00", "20.00", "10.00");
		assertThat(all).extracting(e -> e.getRecordedAt().toInstant()).isSortedAccordingTo((a, b) -> b.compareTo(a));
		assertThat(all).allSatisfy(e -> assertThat(e.getEntryId()).isNotBlank());

		assertThat(api.entries(merchant, null, null).body().getItems()).hasSize(5);
		assertThat(api.entries(merchant, null, null).body().getNextCursor()).isNull();
	}

	@Test
	void entryPagingRejectsBadInput() {
		UUID merchant = openAccount("EUR");

		Response<AccountEntriesPageDto> badCursor = api.entries(merchant, "not-a-cursor", 10);
		assertThat(badCursor.status()).isEqualTo(400);
		assertThat(badCursor.code(0)).isEqualTo("MALFORMED_REQUEST");

		assertThat(api.entries(merchant, null, 500).status()).isEqualTo(400);
		assertThat(api.entries(UUID.randomUUID(), null, 10).status()).isEqualTo(404);
		assertThat(api.entries(merchant, null, 10).body().getItems()).isEmpty();
	}

	@Test
	void theBalanceAsAtAnInstantIsTheBalanceAfterTheLastEntryRecordedByThen() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		Instant beforeAnything = Instant.now().minus(1, ChronoUnit.DAYS);
		TransactionDto first = record("settle", debit(settlement, "100.00"), credit(merchant, "100.00"));
		TransactionDto second = record("payout", debit(merchant, "30.00"), credit(payouts, "30.00"));
		Instant t1 = first.getRecordedAt().toInstant();
		Instant t2 = second.getRecordedAt().toInstant();
		assertThat(t2).isAfter(t1);

		assertThat(balanceAt(merchant, beforeAnything)).isEqualTo("0.00");
		assertThat(balanceAt(merchant, t1.minusNanos(1_000))).isEqualTo("0.00");
		assertThat(balanceAt(merchant, t1)).isEqualTo("100.00");
		assertThat(balanceAt(merchant, t2.minusNanos(1_000))).isEqualTo("100.00");
		assertThat(balanceAt(merchant, t2)).isEqualTo("70.00");
		assertThat(balanceAt(merchant, Instant.now().plus(1, ChronoUnit.HOURS))).isEqualTo("70.00");

		Response<BalanceAtDto> response = api.balanceAt(merchant, t1);
		assertThat(response.body().getAccountId()).isEqualTo(merchant);
		assertThat(response.body().getAt().toInstant()).isEqualTo(t1);

		assertThat(api.balanceAt(UUID.randomUUID(), t1).status()).isEqualTo(404);
		assertThat(api.raw(HttpMethod.GET, "/v1/accounts/" + merchant + "/balance", Map.of(), null).status())
				.isEqualTo(400);
		assertThat(api.raw(HttpMethod.GET, "/v1/accounts/" + merchant + "/balance?at=yesterday", Map.of(), null)
				.status()).isEqualTo(400);
	}

	private String balanceAt(UUID account, Instant at) {
		Response<BalanceAtDto> response = api.balanceAt(account, at);
		assertThat(response.status()).as(response.problem().toString()).isEqualTo(200);
		return response.body().getBalance();
	}
}
