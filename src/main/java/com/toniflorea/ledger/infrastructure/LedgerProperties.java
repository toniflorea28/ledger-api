package com.toniflorea.ledger.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledger")
public record LedgerProperties(@DefaultValue("local") String instanceId, @DefaultValue Idempotency idempotency,
		@DefaultValue Outbox outbox, Topics topics) {

	public record Idempotency(@DefaultValue("PT24H") Duration ttl, @DefaultValue("PT10M") Duration sweepInterval) {
	}

	public record Outbox(@DefaultValue("true") boolean enabled, @DefaultValue("PT0.5S") Duration pollInterval,
			@DefaultValue("PT15S") Duration leaseTtl, @DefaultValue("200") int batchSize,
			@DefaultValue("PT10S") Duration sendTimeout) {
	}

	public record Topics(String paymentSettled, String paymentSettledDlt, String transactionRecorded) {
	}
}
