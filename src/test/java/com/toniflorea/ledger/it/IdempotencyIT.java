package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static com.toniflorea.ledger.support.LedgerClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.support.IntegrationTest;
import com.toniflorea.ledger.support.LedgerClient.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IdempotencyIT extends IntegrationTest {

	@AfterEach
	void theBooksStillBalance() {
		assertLedgerIntegrity();
	}

	@Test
	void retryingOpenAccountReturnsTheFirstAnswerAndOpensOneAccount() {
		String key = key();
		long accounts = count("accounts");

		Response<AccountDto> first = api.openAccount("EUR", "5.00", key);
		Response<AccountDto> retry = api.openAccount("EUR", "5.00", key);

		assertThat(first.status()).isEqualTo(201);
		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.body()).isEqualTo(first.body());
		assertThat(retry.location()).isEqualTo(first.location());
		assertThat(count("accounts")).isEqualTo(accounts + 1);

		record("fund", debit(settlementAccount("EUR"), "7.00"), credit(first.body().getId(), "7.00"));
		Response<AccountDto> later = api.openAccount("EUR", "5.00", key);
		assertThat(later.body().getBalance()).isEqualTo("0.00");
		assertThat(balanceOf(first.body().getId())).isEqualTo("7.00");
	}

	@Test
	void retryingRecordTransactionRecordsItOnce() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		String key = key();

		Response<TransactionDto> first = api.record("settle", key, debit(settlement, "125.50"), credit(merchant, "125.50"));
		Response<TransactionDto> retry = api.record("settle", key, debit(settlement, "125.50"), credit(merchant, "125.50"));

		assertThat(first.status()).isEqualTo(201);
		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.body()).isEqualTo(first.body());
		assertThat(retry.location()).isEqualTo(first.location());
		assertThat(balanceOf(merchant)).isEqualTo("125.50");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM entries WHERE account_id = UUID_TO_BIN(:id)")
				.param("id", merchant.toString()).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void retryingAReversalReversesOnce() {
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		record("settle", debit(settlementAccount("EUR"), "50.00"), credit(merchant, "50.00"));
		TransactionDto payout = record("payout", debit(merchant, "20.00"), credit(payouts, "20.00"));
		String key = key();

		Response<TransactionDto> first = api.reverse(payout.getId(), key, null);
		Response<TransactionDto> retry = api.reverse(payout.getId(), key, null);
		Response<TransactionDto> fresh = api.reverse(payout.getId(), key(), null);

		assertThat(first.status()).isEqualTo(201);
		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.body()).isEqualTo(first.body());
		assertThat(fresh.status()).isEqualTo(409);
		assertThat(fresh.code(0)).isEqualTo("ALREADY_REVERSED");
		assertThat(balanceOf(merchant)).isEqualTo("50.00");
	}

	@Test
	void theSameKeyWithADifferentRequestIsRefusedAndRecordsNothing() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		String key = key();
		api.record("settle", key, debit(settlement, "10.00"), credit(merchant, "10.00"));
		long transactions = count("transactions");

		Response<TransactionDto> different = api.record("settle", key, debit(settlement, "11.00"),
				credit(merchant, "11.00"));

		assertThat(different.status()).isEqualTo(409);
		assertThat(different.code(0)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
		assertThat(count("transactions")).isEqualTo(transactions);
		assertThat(balanceOf(merchant)).isEqualTo("10.00");
	}

	@Test
	void aRefusalIsRememberedEvenAfterTheWorldChanges() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		String key = key();

		Response<TransactionDto> refused = api.record("payout", key, debit(merchant, "10.00"), credit(payouts, "10.00"));
		assertThat(refused.status()).isEqualTo(422);
		assertThat(refused.code(0)).isEqualTo("OVERDRAFT_EXCEEDED");

		record("fund", debit(settlement, "10.00"), credit(merchant, "10.00"));
		Response<TransactionDto> retry = api.record("payout", key, debit(merchant, "10.00"), credit(payouts, "10.00"));

		assertThat(retry.status()).isEqualTo(422);
		assertThat(retry.problem()).isEqualTo(refused.problem());
		assertThat(retry.contentType()).startsWith("application/problem+json");
		assertThat(balanceOf(merchant)).isEqualTo("10.00");
	}

	@Test
	void keysAreScopedPerOperation() {
		String key = key();
		UUID merchant = openAccount("EUR");

		Response<AccountDto> opened = api.openAccount("USD", null, key);
		Response<TransactionDto> recorded = api.record("settle", key, debit(settlementAccount("EUR"), "1.00"),
				credit(merchant, "1.00"));

		assertThat(opened.status()).isEqualTo(201);
		assertThat(recorded.status()).isEqualTo(201);
	}

	@Test
	void concurrentRetriesTakeEffectOnceAndAllGetTheSameAnswer() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		String key = key();
		List<Supplier<Response<TransactionDto>>> work = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			work.add(() -> api.record("settle", key, debit(settlement, "9.99"), credit(merchant, "9.99")));
		}

		List<Response<TransactionDto>> responses = runConcurrently(20, work);

		assertThat(responses).extracting(Response::status).containsOnly(201);
		assertThat(responses).extracting(Response::body).containsOnly(responses.get(0).body());
		assertThat(balanceOf(merchant)).isEqualTo("9.99");
	}

	@Test
	void concurrentRetriesOfARefusalAgreeOnTheRefusal() {
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		String key = key();
		long transactions = count("transactions");
		List<Supplier<Response<TransactionDto>>> work = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			work.add(() -> api.record("payout", key, debit(merchant, "1.00"), credit(payouts, "1.00")));
		}

		List<Response<TransactionDto>> responses = runConcurrently(20, work);

		assertThat(responses).extracting(Response::status).containsOnly(422);
		assertThat(responses).extracting(Response::problem).containsOnly(responses.get(0).problem());
		assertThat(count("transactions")).isEqualTo(transactions);
	}

	private <T> List<T> runConcurrently(int threads, List<Supplier<T>> work) {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Supplier<T> task : work) {
				futures.add(pool.submit((Callable<T>) () -> {
					start.await();
					return task.get();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(2, TimeUnit.MINUTES));
			}
			return results;
		}
		catch (Exception e) {
			throw new AssertionError("concurrent run failed", e);
		}
		finally {
			pool.shutdownNow();
		}
	}
}
