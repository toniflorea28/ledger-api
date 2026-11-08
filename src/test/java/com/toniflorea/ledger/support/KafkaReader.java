package com.toniflorea.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class KafkaReader {

	private final String bootstrapServers;

	public KafkaReader(String bootstrapServers) {
		this.bootstrapServers = bootstrapServers;
	}

	public List<ConsumerRecord<String, String>> readFromBeginning(String topic,
			Predicate<ConsumerRecord<String, String>> keep, Predicate<List<ConsumerRecord<String, String>>> done,
			Duration timeout) {
		List<ConsumerRecord<String, String>> kept = new ArrayList<>();
		Map<String, Object> props = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props, new StringDeserializer(),
				new StringDeserializer())) {
			consumer.subscribe(List.of(topic));
			await().atMost(timeout).untilAsserted(() -> {
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
					if (keep.test(record)) {
						kept.add(record);
					}
				}
				assertThat(done.test(kept)).as("expected records on %s, got %d so far", topic, kept.size()).isTrue();
			});
		}
		return kept;
	}
}
