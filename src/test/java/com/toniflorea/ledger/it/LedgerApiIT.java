package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static com.toniflorea.ledger.support.LedgerClient.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountKindDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.support.IntegrationTest;
import com.toniflorea.ledger.support.LedgerClient.Response;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class LedgerApiIT extends IntegrationTest {

	@Test
	void settlementAccountsAreSeededOnePerCurrencyWithoutALimit() {
		AccountDto[] accounts = api.settlementAccounts().body();

		assertThat(accounts).extracting(a -> a.getCurrency().getValue()).containsExactly("EUR", "JPY", "USD");
		assertThat(accounts).allSatisfy(a -> {
			assertThat(a.getKind()).isEqualTo(AccountKindDto.SETTLEMENT);
			assertThat(a.getOverdraftLimit()).isNull();
			assertThat(a.getAvailableBalance()).isNull();
		});
		assertThat(settlementAccount("EUR")).hasToString("00000000-0000-7000-8000-000000000978");
	}

	@Test
	void theWorkedExamplesFromTheChallenge() {
		UUID settlementEur = settlementAccount("EUR");
		UUID merchant42 = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		UUID fees = openAccount("EUR");
		String settlementBefore = balanceOf(settlementEur);

		Response<TransactionDto> settled = api.record("payment settled", key(),
				debit(settlementEur, "125.50"), credit(merchant42, "125.50"));
		assertThat(settled.status()).isEqualTo(201);
		assertThat(settled.location()).isEqualTo("/v1/transactions/" + settled.body().getId());
		assertThat(balanceOf(merchant42)).isEqualTo("125.50");
		assertThat(api.getAccount(merchant42).body().getAvailableBalance()).isEqualTo("125.50");
		assertThat(settled.body().getEntries()).extracting(EntryDto::getBalanceAfter)
				.containsExactly(minus(settlementBefore, "125.50"), "125.50");

		TransactionDto payout = record("payout", debit(merchant42, "100.00"), credit(payouts, "98.50"),
				credit(fees, "1.50"));
		assertThat(balanceOf(merchant42)).isEqualTo("25.50");
		assertThat(balanceOf(payouts)).isEqualTo("98.50");
		assertThat(balanceOf(fees)).isEqualTo("1.50");

		Response<TransactionDto> reversal = api.reverse(payout.getId(), key(), null);
		assertThat(reversal.status()).isEqualTo(201);
		assertThat(reversal.body().getReverses()).isEqualTo(payout.getId());
		assertThat(reversal.body().getDescription()).isEqualTo("Reversal of " + payout.getId());
		assertThat(reversal.body().getEntries()).extracting(e -> e.getDirection().getValue(), EntryDto::getAmount)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("CREDIT", "100.00"),
						org.assertj.core.groups.Tuple.tuple("DEBIT", "98.50"),
						org.assertj.core.groups.Tuple.tuple("DEBIT", "1.50"));
		assertThat(balanceOf(merchant42)).isEqualTo("125.50");
		assertThat(balanceOf(payouts)).isEqualTo("0.00");
		assertThat(balanceOf(fees)).isEqualTo("0.00");

		TransactionDto original = api.getTransaction(payout.getId()).body();
		assertThat(original.getReversedBy()).isEqualTo(reversal.body().getId());
		assertThat(original.getEntries()).isEqualTo(payout.getEntries());

		Response<TransactionDto> again = api.reverse(payout.getId(), key(), null);
		assertThat(again.status()).isEqualTo(409);
		assertThat(again.code(0)).isEqualTo("ALREADY_REVERSED");

		Response<TransactionDto> ofReversal = api.reverse(reversal.body().getId(), key(), null);
		assertThat(ofReversal.status()).isEqualTo(409);
		assertThat(ofReversal.code(0)).isEqualTo("CANNOT_REVERSE_REVERSAL");

		UUID merchant7 = openAccount("JPY");
		TransactionDto yen = record("payment settled", debit(settlementAccount("JPY"), "15000"),
				credit(merchant7, "15000"));
		assertThat(yen.getCurrency().getValue()).isEqualTo("JPY");
		assertThat(balanceOf(merchant7)).isEqualTo("15000");

		assertLedgerIntegrity();
	}

	@Test
	void theRefusalsFromTheChallengeNameTheRuleAndTheFaultyPart() {
		UUID settlementEur = settlementAccount("EUR");
		UUID merchant42 = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		UUID merchant7 = openAccount("JPY");
		UUID payoutsJpy = openAccount("JPY");
		record("settle", debit(settlementEur, "125.50"), credit(merchant42, "125.50"));
		record("payout", debit(merchant42, "100.00"), credit(payouts, "100.00"));

		Response<TransactionDto> unbalanced = api.record("x", key(), debit(merchant42, "100.00"),
				credit(payouts, "99.00"));
		assertRefused(unbalanced, "UNBALANCED", "/entries");

		Response<TransactionDto> overdraft = api.record("x", key(), debit(merchant42, "50.00"),
				credit(payouts, "50.00"));
		assertRefused(overdraft, "OVERDRAFT_EXCEEDED", "/entries/0/accountId");
		assertThat(overdraft.detail(0)).contains("-24.50");

		Response<TransactionDto> scale = api.record("x", key(), debit(merchant7, "100.5"),
				credit(payoutsJpy, "100.5"));
		assertRefused(scale, "SCALE_EXCEEDED", "/entries/0/amount");
		assertThat(scale.errors()).hasSize(2);

		Response<TransactionDto> mixed = api.record("x", key(), debit(merchant42, "10.00"), credit(merchant7, "10"));
		assertRefused(mixed, "CURRENCY_MISMATCH", "/entries/1/accountId");

		Response<TransactionDto> single = api.record("x", key(), credit(merchant42, "10.00"));
		assertRefused(single, "TOO_FEW_ENTRIES", "/entries");

		Response<TransactionDto> unknown = api.record("x", key(), debit(merchant42, "10.00"),
				credit(UUID.randomUUID(), "10.00"));
		assertRefused(unknown, "UNKNOWN_ACCOUNT", "/entries/1/accountId");

		assertThat(balanceOf(merchant42)).isEqualTo("25.50");
	}

	@Test
	void openingAnAccountChecksTheCurrencyAndTheLimit() {
		assertRefused(api.openAccount("EURO", null, key()), "INVALID_CURRENCY_CODE", "/currency");
		assertRefused(api.openAccount("ABC", null, key()), "INVALID_CURRENCY_CODE", "/currency");
		assertRefused(api.openAccount("GBP", null, key()), "UNSUPPORTED_CURRENCY", "/currency");
		assertRefused(api.openAccount("JPY", "10.5", key()), "SCALE_EXCEEDED", "/overdraftLimit");

		Response<AccountDto> withLimit = api.openAccount("EUR", "30.00", key());
		assertThat(withLimit.status()).isEqualTo(201);
		assertThat(withLimit.body().getOverdraftLimit()).isEqualTo("30.00");
		assertThat(withLimit.body().getAvailableBalance()).isEqualTo("30.00");
		assertThat(withLimit.body().getKind()).isEqualTo(AccountKindDto.REGULAR);
		assertThat(withLimit.location()).isEqualTo("/v1/accounts/" + withLimit.body().getId());
	}

	@Test
	void malformedRequestsAreRejectedBeforeTheLedgerSeesThem() {
		UUID merchant = openAccount("EUR");

		Response<String> badJson = api.raw(HttpMethod.POST, "/v1/transactions",
				Map.of("Idempotency-Key", key(), "Content-Type", "application/json"), "{\"description\": ");
		assertThat(badJson.status()).isEqualTo(400);
		assertThat(badJson.code(0)).isEqualTo("MALFORMED_REQUEST");
		assertThat(badJson.contentType()).startsWith("application/problem+json");

		Response<String> noKey = api.raw(HttpMethod.POST, "/v1/accounts", Map.of("Content-Type", "application/json"),
				"{\"currency\":\"EUR\"}");
		assertThat(noKey.status()).isEqualTo(400);
		assertThat(noKey.code(0)).isEqualTo("IDEMPOTENCY_KEY_MISSING");

		Response<TransactionDto> badAmount = api.record("x", key(), debit(merchant, "abc"),
				credit(merchant, "10.00"));
		assertThat(badAmount.status()).isEqualTo(400);
		assertThat(badAmount.code(0)).isEqualTo("MALFORMED_REQUEST");
		assertThat(badAmount.pointer(0)).isEqualTo("/entries/0/amount");

		Response<String> noDescription = api.raw(HttpMethod.POST, "/v1/transactions",
				Map.of("Idempotency-Key", key(), "Content-Type", "application/json"),
				"{\"entries\":[]}");
		assertThat(noDescription.status()).isEqualTo(400);
		assertThat(noDescription.errors()).extracting(e -> e.get("pointer")).containsExactly("/description");

		Response<TransactionDto> missing = api.getTransaction(UUID.randomUUID());
		assertThat(missing.status()).isEqualTo(404);
		assertThat(missing.code(0)).isEqualTo("NOT_FOUND");
		assertThat(api.getAccount(UUID.randomUUID()).status()).isEqualTo(404);

		assertThat(api.raw(HttpMethod.GET, "/v1/accounts/not-a-uuid", Map.of(), null).status()).isEqualTo(400);
	}

	@Test
	void problemsFollowRfc9457() {
		Response<AccountDto> refused = api.openAccount("GBP", null, key());

		assertThat(refused.status()).isEqualTo(422);
		assertThat(refused.contentType()).startsWith("application/problem+json");
		assertThat(refused.problem())
				.containsEntry("type", "urn:ledger:problem:refused")
				.containsEntry("title", "Refused by a ledger rule")
				.containsEntry("status", 422)
				.containsKey("detail")
				.containsKey("errors");
		assertThat(refused.errors().get(0)).containsKeys("code", "detail", "pointer");
	}

	@Test
	void recordedAtComesFromTheDatabaseClockInUtc() {
		UUID merchant = openAccount("EUR");
		Instant before = Instant.now();

		TransactionDto tx = record("settle", debit(settlementAccount("EUR"), "1.00"), credit(merchant, "1.00"));

		Instant recordedAt = tx.getRecordedAt().toInstant();
		assertThat(recordedAt).isCloseTo(before, within(30, ChronoUnit.SECONDS));
		Timestamp stored = jdbc.sql("SELECT recorded_at FROM transactions WHERE id = UUID_TO_BIN(:id)")
				.param("id", tx.getId().toString())
				.query(Timestamp.class).single();
		assertThat(stored.toInstant()).isEqualTo(recordedAt);
		Timestamp entryStored = jdbc.sql("SELECT MIN(recorded_at) FROM entries WHERE transaction_id = UUID_TO_BIN(:id)")
				.param("id", tx.getId().toString())
				.query(Timestamp.class).single();
		assertThat(entryStored.toInstant()).isEqualTo(recordedAt);
	}

	private static void assertRefused(Response<?> response, String code, String pointer) {
		assertThat(response.status()).as(response.problem().toString()).isEqualTo(422);
		assertThat(response.code(0)).isEqualTo(code);
		assertThat(response.pointer(0)).isEqualTo(pointer);
	}

	private static String minus(String balance, String amount) {
		long cents = Math.round(Double.parseDouble(balance) * 100) - Math.round(Double.parseDouble(amount) * 100);
		return String.format("%s%d.%02d", cents < 0 ? "-" : "", Math.abs(cents) / 100, Math.abs(cents) % 100);
	}
}
