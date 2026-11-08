package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.idempotency.IdempotencyRecord;
import com.toniflorea.ledger.application.idempotency.IdempotencyStore;
import com.toniflorea.ledger.application.idempotency.StoredResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

	private final JdbcClient jdbc;

	public JdbcIdempotencyStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insertPending(String scope, String key, byte[] fingerprint, Instant createdAt, Instant expiresAt) {
		try {
			jdbc.sql("""
					INSERT INTO idempotency_keys (scope, idem_key, request_fingerprint, created_at, expires_at)
					VALUES (:scope, :key, :fingerprint, :createdAt, :expiresAt)
					""")
					.param("scope", scope)
					.param("key", key)
					.param("fingerprint", fingerprint)
					.param("createdAt", Timestamp.from(createdAt))
					.param("expiresAt", Timestamp.from(expiresAt))
					.update();
		}
		catch (DuplicateKeyException e) {
			throw new KeyAlreadyExists();
		}
		catch (PessimisticLockingFailureException e) {
			throw new KeyLocked();
		}
	}

	@Override
	public void complete(String scope, String key, StoredResponse response) {
		jdbc.sql("""
				UPDATE idempotency_keys
				SET response_status = :status, response_body = :body, response_location = :location
				WHERE scope = :scope AND idem_key = :key
				""")
				.param("status", response.status())
				.param("body", response.body())
				.param("location", response.location())
				.param("scope", scope)
				.param("key", key)
				.update();
	}

	@Override
	public void insertCompleted(String scope, String key, byte[] fingerprint, StoredResponse response,
			Instant createdAt, Instant expiresAt) {
		try {
			jdbc.sql("""
					INSERT INTO idempotency_keys (scope, idem_key, request_fingerprint, response_status, response_body,
					                              response_location, created_at, expires_at)
					VALUES (:scope, :key, :fingerprint, :status, :body, :location, :createdAt, :expiresAt)
					""")
					.param("scope", scope)
					.param("key", key)
					.param("fingerprint", fingerprint)
					.param("status", response.status())
					.param("body", response.body())
					.param("location", response.location())
					.param("createdAt", Timestamp.from(createdAt))
					.param("expiresAt", Timestamp.from(expiresAt))
					.update();
		}
		catch (DuplicateKeyException e) {
			throw new KeyAlreadyExists();
		}
		catch (PessimisticLockingFailureException e) {
			throw new KeyLocked();
		}
	}

	@Override
	public Optional<IdempotencyRecord> find(String scope, String key) {
		return jdbc.sql("""
				SELECT request_fingerprint, response_status, response_body, response_location
				FROM idempotency_keys WHERE scope = :scope AND idem_key = :key
				""")
				.param("scope", scope)
				.param("key", key)
				.query(this::map)
				.optional();
	}

	@Override
	public int deleteExpired(Instant now) {
		return jdbc.sql("DELETE FROM idempotency_keys WHERE expires_at < :now")
				.param("now", Timestamp.from(now))
				.update();
	}

	private IdempotencyRecord map(ResultSet rs, int rowNum) throws SQLException {
		int status = rs.getInt("response_status");
		Optional<StoredResponse> response = rs.wasNull() ? Optional.empty()
				: Optional.of(new StoredResponse(status, rs.getString("response_body"), rs.getString("response_location")));
		return new IdempotencyRecord(rs.getBytes("request_fingerprint"), response);
	}
}
