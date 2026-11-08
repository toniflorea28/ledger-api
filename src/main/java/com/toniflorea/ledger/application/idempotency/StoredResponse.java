package com.toniflorea.ledger.application.idempotency;

import java.util.Objects;

public record StoredResponse(int status, String body, String location) {

	public StoredResponse {
		Objects.requireNonNull(body, "body");
	}

	public boolean isSuccess() {
		return status < 400;
	}
}
