package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.adapter.outbound.messaging.OutboxRelay;
import com.toniflorea.ledger.support.IntegrationTest;
import com.toniflorea.ledger.support.KafkaReader;
import com.toniflorea.ledger.support.LedgerClient.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.kafka.KafkaContainer;

class TraceIT extends IntegrationTest {

	private static final String ANNOUNCEMENTS = "ledger.transaction-recorded.v1";

	@Autowired
	KafkaContainer kafkaContainer;

	@Test
	void anHttpRequestsTraceFollowsItThroughTheLogsIntoTheAnnouncement() {
		UUID merchant = openAccount("EUR");
		String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
		Map<String, String> headers = Map.of("Idempotency-Key", UUID.randomUUID().toString(),
				"traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");

		Response<TransactionDto> response = api.record("traced", headers, debit(settlementAccount("EUR"), "1.00"),
				credit(merchant, "1.00"));

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.headers()).containsEntry("x-trace-id", traceId);
		String stored = jdbc.sql("SELECT headers FROM outbox_events WHERE aggregate_id = UUID_TO_BIN(:id)")
				.param("id", response.body().getId().toString()).query(String.class).single();
		assertThat(stored).contains("traceparent").contains(traceId);
		ConsumerRecord<String, String> announced = announcementOf(response.body().getId());
		assertThat(header(announced, "traceparent")).contains(traceId);
	}

	@Test
	void everyResponseCarriesATraceIdEvenWithoutAnIncomingOne() {
		UUID merchant = openAccount("EUR");

		Response<?> response = api.getAccount(merchant);

		assertThat(response.headers().get("x-trace-id")).matches("[0-9a-f]{32}");
	}

	@Test
	void aPaymentMessagesTraceFollowsItIntoTheAnnouncement() {
		UUID merchant = openAccount("EUR");
		String traceId = "a0d3f1c2b4e5968778695a4b3c2d1e0f";
		String paymentId = "pay_" + UUID.randomUUID();
		ProducerRecord<String, String> message = new ProducerRecord<>("payments.settled", paymentId,
				"{\"eventId\":\"evt_t\",\"paymentId\":\"" + paymentId + "\",\"merchantAccountId\":\"" + merchant
						+ "\",\"amount\":\"2.00\",\"currency\":\"EUR\",\"occurredAt\":\"2026-01-01T00:00:00Z\"}");
		message.headers().add("traceparent", ("00-" + traceId + "-b7ad6b7169203331-01").getBytes(StandardCharsets.UTF_8));

		sendWithoutInstrumentation(message);

		await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(balanceOf(merchant)).isEqualTo("2.00"));
		UUID transactionId = UUID.fromString(jdbc
				.sql("SELECT BIN_TO_UUID(transaction_id) FROM processed_payments WHERE payment_id = :p")
				.param("p", paymentId).query(String.class).single());
		assertThat(header(announcementOf(transactionId), "traceparent")).contains(traceId);
	}

	/**
	 * The application's KafkaTemplate is observed and would inject its own traceparent
	 * over the one this test sets, so the message is sent with a plain producer, as
	 * the payment system would.
	 */
	private void sendWithoutInstrumentation(ProducerRecord<String, String> message) {
		Map<String, Object> props = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
		try (KafkaProducer<String, String> producer = new KafkaProducer<>(props, new StringSerializer(),
				new StringSerializer())) {
			producer.send(message).get();
		}
		catch (Exception e) {
			throw new AssertionError("could not send message", e);
		}
	}

	private ConsumerRecord<String, String> announcementOf(UUID transactionId) {
		List<ConsumerRecord<String, String>> records = new KafkaReader(kafkaContainer.getBootstrapServers())
				.readFromBeginning(ANNOUNCEMENTS,
						record -> transactionId.toString().equals(header(record, OutboxRelay.AGGREGATE_ID_HEADER)),
						found -> !found.isEmpty(), Duration.ofSeconds(30));
		return records.get(0);
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}
}
