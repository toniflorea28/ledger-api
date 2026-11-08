package com.toniflorea.ledger.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.infrastructure.KafkaConfig;
import com.toniflorea.ledger.support.IntegrationTest;
import com.toniflorea.ledger.support.KafkaReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.HashMap;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

class SettlementIT extends IntegrationTest {

	private static final String TOPIC = "payments.settled";
	private static final String DLT = "payments.settled.dlt";

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	KafkaContainer kafkaContainer;

	@AfterEach
	void theBooksStillBalance() {
		assertLedgerIntegrity();
	}

	@Test
	void example1_aSettledPaymentDebitsSettlementAndCreditsTheMerchant() {
		UUID merchant = openAccount("EUR");
		String paymentId = "pay_" + UUID.randomUUID();

		send(paymentId, payment("evt_1", paymentId, merchant, "125.50", "EUR"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(balanceOf(merchant)).isEqualTo("125.50"));
		Map<String, Object> processed = jdbc.sql("""
				SELECT event_id, BIN_TO_UUID(transaction_id) AS transaction_id
				FROM processed_payments WHERE payment_id = :paymentId
				""").param("paymentId", paymentId).query().singleRow();
		assertThat(processed).containsEntry("event_id", "evt_1");
		TransactionDto tx = api.getTransaction(UUID.fromString((String) processed.get("transaction_id"))).body();
		assertThat(tx.getDescription()).isEqualTo("Payment " + paymentId + " settled");
		assertThat(tx.getEntries()).extracting(e -> e.getDirection().getValue()).containsExactly("DEBIT", "CREDIT");
		assertThat(tx.getEntries().get(0).getAccountId()).isEqualTo(settlementAccount("EUR"));
		assertThat(count("outbox_events")).isGreaterThan(0);
	}

	@Test
	void example4_aYenPaymentSettlesWithoutDecimals() {
		UUID merchant = openAccount("JPY");
		String paymentId = "pay_" + UUID.randomUUID();

		send(paymentId, payment("evt_jpy", paymentId, merchant, "15000", "JPY"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(balanceOf(merchant)).isEqualTo("15000"));
	}

	@Test
	void redeliveryUnderTheSameOrANewEventIdIsRecordedOnce() {
		UUID merchant = openAccount("EUR");
		String paymentId = "pay_" + UUID.randomUUID();
		String message = payment("evt_a", paymentId, merchant, "40.00", "EUR");

		send(paymentId, message);
		send(paymentId, message);
		send(paymentId, payment("evt_b", paymentId, merchant, "40.00", "EUR"));
		UUID marker = openAccount("EUR");
		String markerPayment = "pay_" + UUID.randomUUID();
		send(markerPayment, payment("evt_marker", markerPayment, marker, "1.00", "EUR"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(balanceOf(marker)).isEqualTo("1.00"));
		assertThat(balanceOf(merchant)).isEqualTo("40.00");
		assertThat(jdbc.sql("SELECT COUNT(*) FROM processed_payments WHERE payment_id = :p").param("p", paymentId)
				.query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM entries WHERE account_id = UUID_TO_BIN(:id)")
				.param("id", merchant.toString()).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void messagesThatCannotBeRecordedAreDeadLetteredWithTheReasonAndDoNotBlockTheRest() {
		UUID merchantEur = openAccount("EUR");
		UUID merchantJpy = openAccount("JPY");
		String malformed = "malformed_" + UUID.randomUUID();
		String unknown = "pay_" + UUID.randomUUID();
		String wrongCurrency = "pay_" + UUID.randomUUID();
		String badScale = "pay_" + UUID.randomUUID();
		String missingField = "pay_" + UUID.randomUUID();
		String good = "pay_" + UUID.randomUUID();

		send(malformed, "{\"eventId\": \"evt\", \"paymentId\": ");
		UUID unknownMerchant = UUID.randomUUID();
		send(unknown, payment("evt_u", unknown, unknownMerchant, "10.00", "EUR"));
		send(wrongCurrency, payment("evt_w", wrongCurrency, merchantJpy, "10.00", "EUR"));
		send(badScale, payment("evt_s", badScale, merchantJpy, "100.5", "JPY"));
		send(missingField, "{\"eventId\":\"evt_m\",\"paymentId\":\"" + missingField + "\",\"amount\":\"1.00\",\"currency\":\"EUR\"}");
		send(good, payment("evt_g", good, merchantEur, "2.00", "EUR"));

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(balanceOf(merchantEur)).isEqualTo("2.00"));
		assertThat(balanceOf(merchantJpy)).isEqualTo("0");

		Map<String, ConsumerRecord<String, String>> dead = readDeadLetters(
				Set.of(malformed, unknown, wrongCurrency, badScale, missingField));
		assertThat(reason(dead.get(malformed))).isEqualTo("MALFORMED_REQUEST");
		assertThat(reason(dead.get(unknown))).isEqualTo("UNKNOWN_ACCOUNT");
		assertThat(reason(dead.get(wrongCurrency))).isEqualTo("CURRENCY_MISMATCH");
		assertThat(reason(dead.get(badScale))).isEqualTo("SCALE_EXCEEDED");
		assertThat(reason(dead.get(missingField))).isEqualTo("MALFORMED_REQUEST");
		assertThat(header(dead.get(missingField), KafkaConfig.ERROR_DETAIL_HEADER)).contains("merchantAccountId");
		assertThat(dead.get(unknown).value()).isEqualTo(payment("evt_u", unknown, unknownMerchant, "10.00", "EUR"));
		assertThat(header(dead.get(malformed), "kafka_dlt-original-topic")).isEqualTo(TOPIC);
		assertThat(jdbc.sql("SELECT COUNT(*) FROM processed_payments WHERE payment_id IN (:a, :b, :c, :d)")
				.param("a", unknown).param("b", wrongCurrency).param("c", badScale).param("d", missingField)
				.query(Long.class).single()).isZero();
	}

	private void send(String key, String payload) {
		kafka.send(TOPIC, key, payload).join();
	}

	private static String payment(String eventId, String paymentId, UUID merchant, String amount, String currency) {
		return "{\"eventId\":\"" + eventId + "\",\"paymentId\":\"" + paymentId + "\",\"merchantAccountId\":\"" + merchant
				+ "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency
				+ "\",\"occurredAt\":\"2026-01-01T00:00:00Z\"}";
	}

	private static String reason(ConsumerRecord<String, String> record) {
		assertThat(record).as("dead letter present").isNotNull();
		return header(record, KafkaConfig.ERROR_CODE_HEADER);
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}

	private Map<String, ConsumerRecord<String, String>> readDeadLetters(Set<String> keys) {
		List<ConsumerRecord<String, String>> records = new KafkaReader(kafkaContainer.getBootstrapServers())
				.readFromBeginning(DLT, record -> keys.contains(record.key()),
						found -> found.stream().map(ConsumerRecord::key).collect(Collectors.toSet()).containsAll(keys),
						Duration.ofSeconds(30));
		Map<String, ConsumerRecord<String, String>> byKey = new HashMap<>();
		records.forEach(record -> byKey.put(record.key(), record));
		return byKey;
	}
}
