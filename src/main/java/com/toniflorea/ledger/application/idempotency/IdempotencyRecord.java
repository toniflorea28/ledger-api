package com.toniflorea.ledger.application.idempotency;

import java.util.Arrays;
import java.util.Optional;

public record IdempotencyRecord(byte[] fingerprint, Optional<StoredResponse> response) {

	public boolean matches(byte[] otherFingerprint) {
		return Arrays.equals(fingerprint, otherFingerprint);
	}
}
