package com.toniflorea.ledger.adapter.outbound.messaging;

import com.toniflorea.ledger.application.port.LedgerClock;
import com.toniflorea.ledger.application.port.OutboxRepository;
import com.toniflorea.ledger.application.port.OutboxRepository.Entry;
import com.toniflorea.ledger.application.port.RelayLease;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Publishes outbox rows to Kafka in id order, one active relay at a time.
 *
 * One instance holds a database lease and renews it on every tick; if it dies, another
 * takes over when the lease expires. Rows are sent one by one and acknowledged by the
 * broker before the next, and marked published afterwards, so within a partition
 * (one currency) the first delivery of every event is in recording order. A crash
 * between send and mark republishes the row: consumers must dedupe on eventId.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

	public static final String LEASE = "outbox-relay";
	public static final String EVENT_ID_HEADER = "event-id";
	public static final String EVENT_TYPE_HEADER = "event-type";
	public static final String AGGREGATE_ID_HEADER = "transaction-id";

	private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

	private final OutboxRepository outbox;
	private final RelayLease lease;
	private final KafkaTemplate<String, String> kafka;
	private final TransactionOperations tx;
	private final LedgerClock clock;
	private final RelaySettings settings;
	private final Counter published;
	private final String owner;

	public OutboxRelay(OutboxRepository outbox, RelayLease lease, KafkaTemplate<String, String> kafka,
			TransactionOperations tx, LedgerClock clock, RelaySettings settings, MeterRegistry meters) {
		this.outbox = outbox;
		this.lease = lease;
		this.kafka = kafka;
		this.tx = tx;
		this.clock = clock;
		this.settings = settings;
		this.published = meters.counter("ledger.outbox.published");
		this.owner = settings.instanceId() + "/" + UUID.randomUUID().toString().substring(0, 8);
		meters.gauge("ledger.outbox.unpublished", this, relay -> relay.tx.execute(s -> outbox.countUnpublished()));
	}

	@Scheduled(fixedDelayString = "${ledger.outbox.poll-interval:PT0.5S}", initialDelayString = "PT1S")
	public void relay() {
		while (lease.tryAcquire(LEASE, owner, settings.leaseTtl())) {
			List<Entry> batch = tx.execute(status -> outbox.fetchUnpublished(settings.batchSize()));
			if (batch.isEmpty()) {
				return;
			}
			List<Long> sent = publish(batch);
			if (!sent.isEmpty()) {
				tx.executeWithoutResult(status -> outbox.markPublished(sent, clock.now()));
				published.increment(sent.size());
			}
			if (sent.size() < batch.size()) {
				return;
			}
		}
	}

	private List<Long> publish(List<Entry> batch) {
		List<Long> sent = new ArrayList<>(batch.size());
		for (Entry entry : batch) {
			try {
				kafka.send(record(entry)).get(settings.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
				sent.add(entry.id());
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return sent;
			}
			catch (Exception e) {
				log.warn("could not publish outbox event {} (transaction {}); will retry", entry.eventId(),
						entry.aggregateId(), e);
				return sent;
			}
		}
		return sent;
	}

	private ProducerRecord<String, String> record(Entry entry) {
		ProducerRecord<String, String> record = new ProducerRecord<>(settings.topic(), entry.partitionKey(),
				entry.payload());
		record.headers().add(EVENT_ID_HEADER, bytes(entry.eventId().toString()));
		record.headers().add(EVENT_TYPE_HEADER, bytes(entry.eventType()));
		record.headers().add(AGGREGATE_ID_HEADER, bytes(entry.aggregateId().toString()));
		entry.headers().forEach((name, value) -> record.headers().add(name, bytes(value)));
		return record;
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	public record RelaySettings(String instanceId, String topic, Duration leaseTtl, int batchSize,
			Duration sendTimeout) {
	}
}
