package com.toniflorea.ledger.application.idempotency;

import com.toniflorea.ledger.application.port.LedgerClock;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs an operation at most once per (scope, key).
 *
 * The key row is inserted first, in the same database transaction as the
 * operation's effects, and completed with the rendered response before commit. A
 * crash halfway leaves neither row nor effect, so a retry re-executes cleanly. A
 * concurrent retry blocks on the unique index until the first attempt commits, then
 * replays the stored response; if the first attempt rolled back, it proceeds itself.
 *
 * The operation runs in a nested transaction (a savepoint). A refusal rolls back to
 * the savepoint, undoing the effects but keeping the key row, which is then completed
 * with the refusal: the outcome, success or refusal, commits together with the key.
 */
public final class IdempotentExecutor {

	private static final int MAX_ATTEMPTS = 5;

	private final IdempotencyStore store;
	private final TransactionOperations tx;
	private final TransactionOperations nested;
	private final LedgerClock clock;
	private final Duration ttl;

	public IdempotentExecutor(IdempotencyStore store, TransactionOperations tx, TransactionOperations nested,
			LedgerClock clock, Duration ttl) {
		this.store = store;
		this.tx = tx;
		this.nested = nested;
		this.clock = clock;
		this.ttl = ttl;
	}

	public StoredResponse execute(String scope, String key, byte[] fingerprint, Supplier<StoredResponse> action,
			Function<LedgerException, StoredResponse> renderRefusal) {
		for (int attempt = 1;; attempt++) {
			try {
				return attempt(scope, key, fingerprint, action, renderRefusal);
			}
			catch (IdempotencyStore.KeyLocked locked) {
				if (attempt == MAX_ATTEMPTS) {
					throw LedgerException.of(ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS, "another request with Idempotency-Key '"
							+ key + "' is still being processed; retry shortly");
				}
				pause(attempt);
			}
		}
	}

	private StoredResponse attempt(String scope, String key, byte[] fingerprint, Supplier<StoredResponse> action,
			Function<LedgerException, StoredResponse> renderRefusal) {
		try {
			return tx.execute(status -> {
				Instant now = clock.now();
				try {
					store.insertPending(scope, key, fingerprint, now, now.plus(ttl));
				}
				catch (IdempotencyStore.KeyAlreadyExists exists) {
					throw new Replay();
				}
				StoredResponse response;
				try {
					response = nested.execute(inner -> action.get());
				}
				catch (LedgerException refusal) {
					response = renderRefusal.apply(refusal);
				}
				store.complete(scope, key, response);
				return response;
			});
		}
		catch (Replay replay) {
			return replay(scope, key, fingerprint);
		}
	}

	private StoredResponse replay(String scope, String key, byte[] fingerprint) {
		IdempotencyRecord record = tx.execute(status -> store.find(scope, key))
				.orElseThrow(IdempotencyStore.KeyLocked::new);
		if (!record.matches(fingerprint)) {
			throw LedgerException.of(ErrorCode.IDEMPOTENCY_KEY_REUSED,
					"Idempotency-Key '" + key + "' was already used for a different request");
		}
		return record.response().orElseThrow(IdempotencyStore.KeyLocked::new);
	}

	private static void pause(int attempt) {
		try {
			Thread.sleep(ThreadLocalRandom.current().nextLong(10L, 30L * attempt));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class Replay extends RuntimeException {

		Replay() {
			super(null, null, false, false);
		}
	}
}
