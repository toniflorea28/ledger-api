package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.port.RelayLease;
import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * A row per lease name. The holder renews by re-acquiring; another instance takes
 * over once expires_at (database clock) has passed. Both assignments are evaluated
 * left to right by MySQL, so the second IF sees the owner the first one just set.
 */
@Repository
public class JdbcRelayLease implements RelayLease {

	private final JdbcClient jdbc;

	public JdbcRelayLease(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public boolean tryAcquire(String name, String owner, Duration ttl) {
		jdbc.sql("""
				INSERT INTO relay_lease (name, owner, expires_at)
				VALUES (:name, :owner, DATE_ADD(NOW(6), INTERVAL :ttlMillis * 1000 MICROSECOND))
				ON DUPLICATE KEY UPDATE
				    owner = IF(expires_at < NOW(6) OR owner = :owner, :owner, owner),
				    expires_at = IF(owner = :owner, DATE_ADD(NOW(6), INTERVAL :ttlMillis * 1000 MICROSECOND), expires_at)
				""")
				.param("name", name)
				.param("owner", owner)
				.param("ttlMillis", ttl.toMillis())
				.update();
		String holder = jdbc.sql("SELECT owner FROM relay_lease WHERE name = :name")
				.param("name", name)
				.query(String.class)
				.single();
		return owner.equals(holder);
	}
}
