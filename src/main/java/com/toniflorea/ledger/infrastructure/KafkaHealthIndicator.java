package com.toniflorea.ledger.infrastructure;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {

	private final KafkaAdmin admin;

	public KafkaHealthIndicator(KafkaAdmin admin) {
		this.admin = admin;
	}

	@Override
	public Health health() {
		try {
			String clusterId = admin.clusterId();
			return clusterId == null ? Health.down().withDetail("reason", "no cluster id").build()
					: Health.up().withDetail("clusterId", clusterId).build();
		}
		catch (Exception e) {
			return Health.down(e).build();
		}
	}
}
