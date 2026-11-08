package com.toniflorea.ledger.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledger")
public record LedgerProperties(@DefaultValue("local") String instanceId, @DefaultValue Idempotency idempotency,
		Topics topics) {

	public record Idempotency(@DefaultValue("PT24H") Duration ttl, @DefaultValue("PT10M") Duration sweepInterval) {
	}

	public record Topics(String paymentSettled, String paymentSettledDlt, String transactionRecorded) {
	}
}
