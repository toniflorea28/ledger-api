package com.toniflorea.ledger.application.idempotency;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyStore {

	class KeyAlreadyExists extends RuntimeException {
	}

	class KeyLocked extends RuntimeException {
	}

	void insertPending(String scope, String key, byte[] fingerprint, Instant createdAt, Instant expiresAt);

	void complete(String scope, String key, StoredResponse response);

	void insertCompleted(String scope, String key, byte[] fingerprint, StoredResponse response, Instant createdAt,
			Instant expiresAt);

	Optional<IdempotencyRecord> find(String scope, String key);

	int deleteExpired(Instant now);
}
