package com.toniflorea.ledger.domain.transaction;

public enum Direction {
	DEBIT, CREDIT;

	public Direction flip() {
		return this == DEBIT ? CREDIT : DEBIT;
	}
}
