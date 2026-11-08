package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toniflorea.ledger.application.idempotency.IdempotencyStore;
import com.toniflorea.ledger.application.idempotency.IdempotentExecutor;
import com.toniflorea.ledger.application.idempotency.StoredResponse;
import com.toniflorea.ledger.application.usecase.RecordTransaction;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class IdempotentExecutorIT extends IntegrationTest {

	@Autowired
	IdempotentExecutor executor;

	@Autowired
	IdempotencyStore store;

	@Autowired
	RecordTransaction recordTransaction;

	@Test
	void aCrashAfterTheEffectButBeforeTheAnswerLeavesNoTraceSoTheRetryRunsOnce() {
		UUID merchant = openAccount("EUR");
		RecordTransaction.Command command = new RecordTransaction.Command("settle", List.of(
				new RecordTransaction.Entry(AccountId.of(settlementAccount("EUR")), Direction.DEBIT, "3.00"),
				new RecordTransaction.Entry(AccountId.of(merchant), Direction.CREDIT, "3.00")));
		String key = UUID.randomUUID().toString();
		byte[] fingerprint = new byte[32];
		fingerprint[0] = 7;
		long transactions = count("transactions");

		assertThatThrownBy(() -> executor.execute("test", key, fingerprint, () -> {
			recordTransaction.record(command);
			throw new IllegalStateException("crash before the response is stored");
		}, refusal -> null)).isInstanceOf(IllegalStateException.class);

		assertThat(count("transactions")).isEqualTo(transactions);
		assertThat(store.find("test", key)).isEmpty();
		assertThat(balanceOf(merchant)).isEqualTo("0.00");

		StoredResponse first = executor.execute("test", key, fingerprint, () -> {
			Transaction tx = recordTransaction.record(command);
			return new StoredResponse(201, "{\"id\":\"" + tx.id() + "\"}", null);
		}, refusal -> null);
		StoredResponse replayed = executor.execute("test", key, fingerprint,
				() -> new StoredResponse(500, "must not run", null), refusal -> null);

		assertThat(count("transactions")).isEqualTo(transactions + 1);
		assertThat(replayed).isEqualTo(first);
		assertThat(balanceOf(merchant)).isEqualTo("3.00");
	}

	@Test
	void expiredKeysAreSweptAndTheKeyBecomesUsableAgain() {
		String key = UUID.randomUUID().toString();
		Instant past = Instant.now().minusSeconds(3600);
		store.insertCompleted("test", key, new byte[32], new StoredResponse(201, "{}", null), past.minusSeconds(60),
				past);

		int deleted = store.deleteExpired(Instant.now());

		assertThat(deleted).isGreaterThanOrEqualTo(1);
		assertThat(store.find("test", key)).isEmpty();
	}
}
