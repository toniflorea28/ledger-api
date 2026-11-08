package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.adapter.outbound.messaging.OutboxRelay;
import com.toniflorea.ledger.application.port.RelayLease;
import com.toniflorea.ledger.support.IntegrationTest;
import com.toniflorea.ledger.support.KafkaReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;

class AnnounceIT extends IntegrationTest {

	private static final String TOPIC = "ledger.transaction-recorded.v1";

	@Autowired
	KafkaContainer kafkaContainer;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	RelayLease relayLease;

	private final Schema schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
			.getSchema(getClass().getResourceAsStream("/schemas/transaction-recorded.v1.schema.json"));

	@Test
	void everyRecordedTransactionIsAnnouncedWithItsDetailsInTheDocumentedShape() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		TransactionDto settled = record("settle", debit(settlement, "125.50"), credit(merchant, "125.50"));
		TransactionDto payout = record("payout", debit(merchant, "100.00"), credit(payouts, "100.00"));
		TransactionDto reversal = api.reverse(payout.getId(), UUID.randomUUID().toString(), null).body();
		String paymentId = "pay_" + UUID.randomUUID();
		kafka.send("payments.settled", paymentId, "{\"eventId\":\"e\",\"paymentId\":\"" + paymentId
				+ "\",\"merchantAccountId\":\"" + merchant + "\",\"amount\":\"4.00\",\"currency\":\"EUR\","
				+ "\"occurredAt\":\"2026-01-01T00:00:00Z\"}").join();
		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(balanceOf(merchant)).isEqualTo("129.50"));
		UUID viaMessage = UUID.fromString(jdbc
				.sql("SELECT BIN_TO_UUID(transaction_id) FROM processed_payments WHERE payment_id = :p")
				.param("p", paymentId).query(String.class).single());
		Set<UUID> expected = Set.of(settled.getId(), payout.getId(), reversal.getId(), viaMessage);

		List<ConsumerRecord<String, String>> announced = readAnnouncements(expected, expected.size());

		assertThat(announced).hasSize(4);
		for (ConsumerRecord<String, String> record : announced) {
			assertThat(schema.validate(record.value(), InputFormat.JSON)).as(record.value()).isEmpty();
			assertThat(record.key()).isEqualTo("EUR");
			assertThat(header(record, OutboxRelay.EVENT_TYPE_HEADER)).isEqualTo("ledger.transaction-recorded");
			JsonNode payload = json.readTree(record.value());
			assertThat(payload.get("transaction").get("id").asString()).isEqualTo(header(record, OutboxRelay.AGGREGATE_ID_HEADER));
			assertThat(payload.get("eventId").asString()).isEqualTo(header(record, OutboxRelay.EVENT_ID_HEADER));
		}
		JsonNode reversalPayload = json.readTree(find(announced, reversal.getId()).value());
		assertThat(reversalPayload.get("transaction").get("reverses").asString()).isEqualTo(payout.getId().toString());
		assertThat(reversalPayload.get("transaction").get("entries").get(0).get("balanceAfter").asString())
				.isEqualTo(reversal.getEntries().get(0).getBalanceAfter());
		JsonNode settledPayload = json.readTree(find(announced, settled.getId()).value());
		assertThat(settledPayload.get("transaction").get("description").asString()).isEqualTo("settle");
		assertThat(settledPayload.get("transaction").get("entries")).hasSize(2);
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL
				AND aggregate_id IN (UUID_TO_BIN(:a), UUID_TO_BIN(:b), UUID_TO_BIN(:c), UUID_TO_BIN(:d))
				""").param("a", settled.getId().toString()).param("b", payout.getId().toString())
				.param("c", reversal.getId().toString()).param("d", viaMessage.toString())
				.query(Long.class).single()).isZero());
	}

	@Test
	void announcementsOfOneCurrencyArriveInRecordingOrderOnOnePartition() {
		UUID settlement = settlementAccount("USD");
		UUID merchant = openAccount("USD");
		List<UUID> recorded = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			recorded.add(record("tick " + i, debit(settlement, "1.00"), credit(merchant, "1.00")).getId());
		}

		List<ConsumerRecord<String, String>> announced = readAnnouncements(Set.copyOf(recorded), recorded.size());

		assertThat(announced).extracting(r -> UUID.fromString(header(r, OutboxRelay.AGGREGATE_ID_HEADER)))
				.containsExactlyElementsOf(recorded);
		assertThat(announced).extracting(ConsumerRecord::partition).containsOnly(announced.get(0).partition());
		assertThat(announced).extracting(ConsumerRecord::key).containsOnly("USD");
	}

	@Test
	void anEventWhoseMarkWasLostIsPublishedAgainRatherThanLost() {
		UUID merchant = openAccount("JPY");
		TransactionDto tx = record("yen", debit(settlementAccount("JPY"), "100"), credit(merchant, "100"));
		awaitPublished(tx.getId());

		jdbc.sql("UPDATE outbox_events SET published_at = NULL WHERE aggregate_id = UUID_TO_BIN(:id)")
				.param("id", tx.getId().toString()).update();
		awaitPublished(tx.getId());

		List<ConsumerRecord<String, String>> announced = readAnnouncements(Set.of(tx.getId()), 2);
		assertThat(announced).extracting(r -> header(r, OutboxRelay.EVENT_ID_HEADER)).containsOnly(
				header(announced.get(0), OutboxRelay.EVENT_ID_HEADER));
		assertThat(announced).extracting(ConsumerRecord::value).containsOnly(announced.get(0).value());
	}

	@Test
	void onlyOneRelayHoldsTheLeaseUntilItExpires() throws InterruptedException {
		String name = "lease-" + UUID.randomUUID();

		assertThat(relayLease.tryAcquire(name, "instance-a", Duration.ofSeconds(2))).isTrue();
		assertThat(relayLease.tryAcquire(name, "instance-b", Duration.ofSeconds(2))).isFalse();
		assertThat(relayLease.tryAcquire(name, "instance-a", Duration.ofSeconds(2))).isTrue();

		Thread.sleep(2_500);

		assertThat(relayLease.tryAcquire(name, "instance-b", Duration.ofSeconds(2))).isTrue();
		assertThat(relayLease.tryAcquire(name, "instance-a", Duration.ofSeconds(2))).isFalse();
	}

	@Test
	void readinessIncludesTheDatabaseAndTheBroker() {
		String body = api.raw(HttpMethod.GET, "/actuator/health/readiness", Map.of(), null).body();

		assertThat(body).contains("\"status\":\"UP\"").contains("\"kafka\"").contains("\"db\"");
	}

	private void awaitPublished(UUID transactionId) {
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(jdbc.sql("""
				SELECT COUNT(*) FROM outbox_events
				WHERE aggregate_id = UUID_TO_BIN(:id) AND published_at IS NOT NULL
				""").param("id", transactionId.toString()).query(Long.class).single()).isEqualTo(1L));
	}

	private List<ConsumerRecord<String, String>> readAnnouncements(Set<UUID> transactionIds, int atLeast) {
		Set<String> ids = transactionIds.stream().map(UUID::toString).collect(Collectors.toSet());
		return new KafkaReader(kafkaContainer.getBootstrapServers()).readFromBeginning(TOPIC,
				record -> ids.contains(header(record, OutboxRelay.AGGREGATE_ID_HEADER)),
				found -> found.size() >= atLeast, Duration.ofSeconds(30));
	}

	private static ConsumerRecord<String, String> find(List<ConsumerRecord<String, String>> records, UUID id) {
		return records.stream().filter(r -> id.toString().equals(header(r, OutboxRelay.AGGREGATE_ID_HEADER)))
				.findFirst().orElseThrow();
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}
}
