package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.port.LedgerClock;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The database is the one clock all instances share, so recorded_at is monotonic
 * per account regardless of instance clock skew. Read as epoch microseconds to
 * stay independent of session time zones.
 */
@Component
public class JdbcLedgerClock implements LedgerClock {

	private final JdbcClient jdbc;

	public JdbcLedgerClock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Instant now() {
		long micros = jdbc.sql("SELECT CAST(UNIX_TIMESTAMP(NOW(6)) * 1000000 AS UNSIGNED)")
				.query(Long.class)
				.single();
		return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000L);
	}
}
