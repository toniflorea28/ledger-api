package com.toniflorea.ledger.e2e;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static com.toniflorea.ledger.support.LedgerClient.key;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountEntriesPageDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.support.KafkaReader;
import com.toniflorea.ledger.support.LedgerClient;
import com.toniflorea.ledger.support.LedgerClient.Response;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs against the docker-compose stack through its gateway (two ledger instances
 * behind nginx), not against Testcontainers. Enabled by run.sh via -De2e.baseUrl.
 */
@EnabledIfSystemProperty(named = "e2e.baseUrl", matches = ".+")
class StackE2EIT {

	static final String BASE_URL = System.getProperty("e2e.baseUrl", "http://localhost:8080");
	static final String KAFKA = System.getProperty("e2e.kafka", "localhost:29092");
	static final Set<String> instancesSeen = new HashSet<>();

	static LedgerClient api;

	@BeforeAll
	static void client() {
		api = new LedgerClient(BASE_URL, JsonMapper.builder().build());
	}

	@Test
	void theGatewayAnswersFromTwoHealthyInstances() {
		for (int i = 0; i < 10; i++) {
			Response<String> health = api.raw(HttpMethod.GET, "/actuator/health", Map.of(), null);
			assertThat(health.status()).isEqualTo(200);
			assertThat(health.body()).contains("\"status\":\"UP\"");
			instancesSeen.add(health.headers().get("x-instance-id"));
		}
		assertThat(instancesSeen).hasSize(2);
	}

	@Test
	void theWorkedExampleRunsAcrossInstances() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		UUID fees = openAccount("EUR");

		TransactionDto settled = record("settle", debit(settlement, "125.50"), credit(merchant, "125.50"));
		TransactionDto payout = record("payout", debit(merchant, "100.00"), credit(payouts, "98.50"), credit(fees, "1.50"));
		Response<TransactionDto> reversal = api.reverse(payout.getId(), key(), null);
		Response<TransactionDto> refused = api.record("too much", key(), debit(merchant, "200.00"), credit(payouts, "200.00"));

		assertThat(reversal.status()).isEqualTo(201);
		assertThat(refused.status()).isEqualTo(422);
		assertThat(refused.code(0)).isEqualTo("OVERDRAFT_EXCEEDED");
		assertThat(api.getAccount(merchant).body().getBalance()).isEqualTo("125.50");
		assertThat(api.getAccount(payouts).body().getBalance()).isEqualTo("0.00");
		assertThat(api.getTransaction(settled.getId()).body().getEntries()).hasSize(2);
		assertThat(api.getTransaction(payout.getId()).body().getReversedBy()).isEqualTo(reversal.body().getId());
	}

	@Test
	void aRetryThatLandsOnTheOtherInstanceGetsTheSameAnswer() {
		String key = key();

		Response<AccountDto> first = api.openAccount("USD", "10.00", key);
		Response<AccountDto> retry = api.openAccount("USD", "10.00", key);

		assertThat(first.status()).isEqualTo(201);
		assertThat(retry.status()).isEqualTo(201);
		assertThat(retry.body()).isEqualTo(first.body());
		assertThat(retry.location()).isEqualTo(first.location());
		instancesSeen.add(first.headers().get("x-instance-id"));
		instancesSeen.add(retry.headers().get("x-instance-id"));
	}

	@Test
	void moneyMovingBothWaysFromManyClientsAcrossInstancesStaysExact() {
		UUID settlement = settlementAccount("EUR");
		UUID a = openAccount("EUR");
		UUID b = openAccount("EUR");
		record("fund a", debit(settlement, "50.00"), credit(a, "50.00"));
		record("fund b", debit(settlement, "50.00"), credit(b, "50.00"));
		List<Supplier<Response<TransactionDto>>> work = new ArrayList<>();
		for (int t = 0; t < 16; t++) {
			for (int i = 0; i < 20; i++) {
				boolean aToB = (t + i) % 2 == 0;
				work.add(() -> aToB ? api.record("a->b", key(), debit(a, "1.00"), credit(b, "1.00"))
						: api.record("b->a", key(), debit(b, "1.00"), credit(a, "1.00")));
			}
		}

		List<Response<TransactionDto>> responses = runConcurrently(16, work);

		assertThat(responses).extracting(Response::status).isSubsetOf(201, 422);
		long cents = Long.parseLong(api.getAccount(a).body().getBalance().replace(".", ""))
				+ Long.parseLong(api.getAccount(b).body().getBalance().replace(".", ""));
		assertThat(cents).isEqualTo(10_000);
		responses.forEach(r -> instancesSeen.add(r.headers().get("x-instance-id")));
		assertThat(instancesSeen).hasSize(2);
	}

	@Test
	void aSettledPaymentArrivesThroughKafkaAndIsAnnounced() throws Exception {
		UUID merchant = openAccount("JPY");
		String paymentId = "pay_e2e_" + UUID.randomUUID();
		String payload = "{\"eventId\":\"evt_e2e\",\"paymentId\":\"" + paymentId + "\",\"merchantAccountId\":\"" + merchant
				+ "\",\"amount\":\"15000\",\"currency\":\"JPY\",\"occurredAt\":\"2026-01-01T00:00:00Z\"}";
		try (KafkaProducer<String, String> producer = new KafkaProducer<>(
				Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA), new StringSerializer(), new StringSerializer())) {
			producer.send(new ProducerRecord<>("payments.settled", paymentId, payload)).get(30, TimeUnit.SECONDS);
			producer.send(new ProducerRecord<>("payments.settled", paymentId, payload)).get(30, TimeUnit.SECONDS);
		}

		await().atMost(Duration.ofSeconds(60))
				.untilAsserted(() -> assertThat(api.getAccount(merchant).body().getBalance()).isEqualTo("15000"));
		AccountEntriesPageDto entries = api.entries(merchant, null, 10).body();
		assertThat(entries.getItems()).hasSize(1);
		UUID transactionId = entries.getItems().get(0).getTransactionId();

		List<ConsumerRecord<String, String>> announced = new KafkaReader(KAFKA).readFromBeginning(
				"ledger.transaction-recorded.v1",
				record -> transactionId.toString().equals(header(record, "transaction-id")),
				found -> !found.isEmpty(), Duration.ofSeconds(60));
		assertThat(announced.get(0).key()).isEqualTo("JPY");
		JsonNode announcement = JsonMapper.builder().build().readTree(announced.get(0).value());
		assertThat(announcement.get("transaction").get("id").asString()).isEqualTo(transactionId.toString());
		assertThat(announcement.get("transaction").get("description").asString()).contains(paymentId);
		assertThat(announcement.get("transaction").get("entries").get(1).get("amount").asString()).isEqualTo("15000");
		assertThat(header(announced.get(0), "traceparent")).isNotBlank();
	}

	private static UUID settlementAccount(String currency) {
		return Arrays.stream(api.settlementAccounts().body())
				.filter(a -> a.getCurrency().getValue().equals(currency)).findFirst().orElseThrow().getId();
	}

	private static UUID openAccount(String currency) {
		Response<AccountDto> response = api.openAccount(currency, null, key());
		assertThat(response.status()).as(response.problem().toString()).isEqualTo(201);
		return response.body().getId();
	}

	private static TransactionDto record(String description,
			com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryRequestDto... entries) {
		Response<TransactionDto> response = api.record(description, key(), entries);
		assertThat(response.status()).as(response.problem().toString()).isEqualTo(201);
		return response.body();
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		Header header = record.headers().lastHeader(name);
		return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
	}

	private static <T> List<T> runConcurrently(int threads, List<Supplier<T>> work) {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (Supplier<T> task : work) {
				futures.add(pool.submit((Callable<T>) () -> {
					start.await();
					return task.get();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(3, TimeUnit.MINUTES));
			}
			return results;
		}
		catch (Exception e) {
			throw new AssertionError("concurrent run failed", e);
		}
		finally {
			pool.shutdownNow();
		}
	}
}
