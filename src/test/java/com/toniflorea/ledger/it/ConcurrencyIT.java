package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static com.toniflorea.ledger.support.LedgerClient.key;
import static org.assertj.core.api.Assertions.assertThat;

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

class ConcurrencyIT extends IntegrationTest {

	@AfterEach
	void theBooksStillBalance() {
		assertLedgerIntegrity();
	}

	@Test
	void moneyMovingBothWaysBetweenTwoAccountsNeverDeadlocksOrLosesAnUpdate() {
		UUID settlement = settlementAccount("EUR");
		UUID a = openAccount("EUR");
		UUID b = openAccount("EUR");
		record("fund a", debit(settlement, "100.00"), credit(a, "100.00"));
		record("fund b", debit(settlement, "100.00"), credit(b, "100.00"));

		int threads = 16;
		int perThread = 40;
		List<Supplier<Response<TransactionDto>>> work = new ArrayList<>();
		for (int t = 0; t < threads; t++) {
			for (int i = 0; i < perThread; i++) {
				boolean aToB = (t + i) % 2 == 0;
				work.add(() -> aToB
						? api.record("a->b", key(), debit(a, "1.00"), credit(b, "1.00"))
						: api.record("b->a", key(), debit(b, "1.00"), credit(a, "1.00")));
			}
		}

		List<Response<TransactionDto>> responses = runConcurrently(threads, work);

		assertThat(responses).extracting(Response::status).isSubsetOf(201, 422);
		assertThat(responses).filteredOn(r -> r.status() == 422).allSatisfy(
				r -> assertThat(r.code(0)).isEqualTo("OVERDRAFT_EXCEEDED"));
		long balanceA = Long.parseLong(balanceOf(a).replace(".", ""));
		long balanceB = Long.parseLong(balanceOf(b).replace(".", ""));
		assertThat(balanceA + balanceB).isEqualTo(20_000);
		assertThat(balanceA).isNotNegative();
		assertThat(balanceB).isNotNegative();
		long recorded = responses.stream().filter(Response::isSuccess).count();
		assertThat(jdbc.sql("SELECT COUNT(*) FROM entries WHERE account_id = UUID_TO_BIN(:id)")
				.param("id", a.toString()).query(Long.class).single()).isEqualTo(recorded + 1);
	}

	@Test
	void aHotAccountAbsorbsManyConcurrentPostings() {
		UUID settlement = settlementAccount("USD");
		long settlementBefore = Long.parseLong(balanceOf(settlement).replace(".", ""));
		List<UUID> merchants = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			merchants.add(openAccount("USD"));
		}
		List<Supplier<Response<TransactionDto>>> work = merchants.stream()
				.<Supplier<Response<TransactionDto>>>map(m -> () -> api.record("settle", key(),
						debit(settlement, "10.00"), credit(m, "10.00")))
				.toList();

		List<Response<TransactionDto>> responses = runConcurrently(32, work);

		assertThat(responses).extracting(Response::status).containsOnly(201);
		assertThat(Long.parseLong(balanceOf(settlement).replace(".", ""))).isEqualTo(settlementBefore - 100_000);
		merchants.forEach(m -> assertThat(balanceOf(m)).isEqualTo("10.00"));
	}

	@Test
	void concurrentDebitsNeverBreachTheOverdraftLimit() {
		UUID settlement = settlementAccount("EUR");
		UUID account = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		record("fund", debit(settlement, "10.00"), credit(account, "10.00"));

		List<Supplier<Response<TransactionDto>>> work = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			work.add(() -> api.record("drain", key(), debit(account, "10.00"), credit(payouts, "10.00")));
		}

		List<Response<TransactionDto>> responses = runConcurrently(20, work);

		assertThat(responses).filteredOn(Response::isSuccess).hasSize(1);
		assertThat(responses).filteredOn(r -> !r.isSuccess()).hasSize(19)
				.allSatisfy(r -> {
					assertThat(r.status()).isEqualTo(422);
					assertThat(r.code(0)).isEqualTo("OVERDRAFT_EXCEEDED");
				});
		assertThat(balanceOf(account)).isEqualTo("0.00");
		assertThat(balanceOf(payouts)).isEqualTo("10.00");
	}

	private <T> List<T> runConcurrently(int threads, List<Supplier<T>> work) {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Callable<T>> tasks = work.stream().<Callable<T>>map(w -> () -> {
				start.await();
				return w.get();
			}).toList();
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(task));
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
