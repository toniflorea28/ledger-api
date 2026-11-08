package com.toniflorea.ledger.adapter.inbound.messaging;

import com.toniflorea.ledger.application.usecase.RecordSettledPayment;
import java.util.ArrayList;
import java.util.List;

public record PaymentSettledMessage(String eventId, String paymentId, String merchantAccountId, String amount,
		String currency, String occurredAt) {

	List<String> missingFields() {
		List<String> missing = new ArrayList<>();
		if (isBlank(eventId)) {
			missing.add("eventId");
		}
		if (isBlank(paymentId)) {
			missing.add("paymentId");
		}
		if (isBlank(merchantAccountId)) {
			missing.add("merchantAccountId");
		}
		if (isBlank(amount)) {
			missing.add("amount");
		}
		if (isBlank(currency)) {
			missing.add("currency");
		}
		return missing;
	}

	RecordSettledPayment.Command toCommand() {
		return new RecordSettledPayment.Command(eventId, paymentId, merchantAccountId, amount, currency);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
