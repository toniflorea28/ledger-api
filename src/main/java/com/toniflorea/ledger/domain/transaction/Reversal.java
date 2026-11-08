package com.toniflorea.ledger.domain.transaction;

import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.DecimalAmount;
import java.util.Optional;

public final class Reversal {

	private Reversal() {
	}

	public static TransactionDraft draft(Transaction original, Optional<TransactionId> existingReversal,
			Optional<String> description) {
		if (original.isReversal()) {
			throw LedgerException.of(ErrorCode.CANNOT_REVERSE_REVERSAL,
					"transaction " + original.id() + " is itself a reversal (of " + original.reverses()
							+ ") and cannot be reversed");
		}
		existingReversal.ifPresent(reversal -> {
			throw LedgerException.of(ErrorCode.ALREADY_REVERSED,
					"transaction " + original.id() + " was already reversed by transaction " + reversal);
		});
		var entries = original.entries().stream()
				.map(e -> new EntryDraft(e.accountId(), e.direction().flip(),
						DecimalAmount.parse(e.amount().toDecimalString(), null)))
				.toList();
		return new TransactionDraft(description.orElse("Reversal of " + original.id()), entries, original.id());
	}
}
