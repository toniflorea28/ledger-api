package com.toniflorea.ledger.adapter.inbound.messaging;

import com.toniflorea.ledger.domain.error.ErrorCode;

public class PoisonMessage extends RuntimeException {

	private final ErrorCode code;

	public PoisonMessage(ErrorCode code, String detail) {
		super(detail);
		this.code = code;
	}

	public ErrorCode code() {
		return code;
	}
}
