package com.toniflorea.ledger.adapter.inbound.rest;

class NotImplemented extends RuntimeException {

	NotImplemented(String feature) {
		super(feature + " is not implemented yet");
	}
}
