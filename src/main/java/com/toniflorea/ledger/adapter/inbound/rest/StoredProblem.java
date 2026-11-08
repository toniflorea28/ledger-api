package com.toniflorea.ledger.adapter.inbound.rest;

class StoredProblem extends RuntimeException {

	private final int status;
	private final String body;

	StoredProblem(int status, String body) {
		super("stored problem " + status, null, false, false);
		this.status = status;
		this.body = body;
	}

	int status() {
		return status;
	}

	String body() {
		return body;
	}
}
