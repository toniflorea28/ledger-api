package com.toniflorea.ledger.infrastructure;

import com.toniflorea.ledger.application.idempotency.IdempotencyStore;
import com.toniflorea.ledger.application.port.LedgerClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

@Component
public class IdempotencyKeySweeper {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyKeySweeper.class);

	private final IdempotencyStore store;
	private final LedgerClock clock;
	private final TransactionOperations tx;

	public IdempotencyKeySweeper(IdempotencyStore store, LedgerClock clock, TransactionOperations tx) {
		this.store = store;
		this.clock = clock;
		this.tx = tx;
	}

	@Scheduled(fixedDelayString = "${ledger.idempotency.sweep-interval:PT10M}", initialDelayString = "PT1M")
	public void sweep() {
		Integer deleted = tx.execute(status -> store.deleteExpired(clock.now()));
		if (deleted != null && deleted > 0) {
			log.info("swept {} expired idempotency keys", deleted);
		}
	}
}
