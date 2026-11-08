package com.toniflorea.ledger.bdd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountKindDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.DirectionDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.infrastructure.KafkaConfig;
import com.toniflorea.ledger.support.KafkaReader;
import com.toniflorea.ledger.support.LedgerClient;
import com.toniflorea.ledger.support.LedgerClient.Response;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.assertj.core.groups.Tuple;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

public class LedgerSteps {

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	JsonMapper json;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	KafkaContainer kafkaContainer;

	private LedgerClient api;
	private final Map<String, UUID> accounts = new LinkedHashMap<>();
	private final Map<String, String> currencies = new HashMap<>();
	private final Map<String, TransactionDto> transactions = new HashMap<>();
	private final Map<String, String> balancesBefore = new HashMap<>();
	private final Map<String, Long> countsBefore = new HashMap<>();
	private final Map<String, String> messageKeys = new HashMap<>();
	private Response<?> previousResponse;
	private Response<?> lastResponse;
	private String idempotencyKey;
	private Supplier<Response<?>> lastRequest;
	private String lastPaymentId;
	private String lastPaymentMessage;

	@Before
	public void client() {
		api = new LedgerClient("http://localhost:" + port, json);
		for (AccountDto settlement : api.settlementAccounts().body()) {
			String alias = "Settlement " + settlement.getCurrency().getValue();
			accounts.put(alias, settlement.getId());
			currencies.put(alias, settlement.getCurrency().getValue());
		}
	}

	@Given("an account {string} in {word}")
	public void anAccountIn(String alias, String currency) {
		Response<AccountDto> response = api.openAccount(currency, null, LedgerClient.key());
		assertThat(response.status()).as(response.problem().toString()).isEqualTo(201);
		accounts.put(alias, response.body().getId());
		currencies.put(alias, currency);
	}

	@When("an account is opened in {word} with an overdraft limit of {word}")
	public void anAccountIsOpenedWithLimit(String currency, String limit) {
		send(() -> api.openAccount(currency, limit, LedgerClient.key()));
		rememberOpened(currency);
	}

	@When("an account is opened in {word}")
	public void anAccountIsOpened(String currency) {
		send(() -> api.openAccount(currency, null, LedgerClient.key()));
		rememberOpened(currency);
	}

	@When("an account is opened in {word} with that key")
	public void anAccountIsOpenedWithThatKey(String currency) {
		send(() -> api.openAccount(currency, null, idempotencyKey));
		rememberOpened(currency);
	}

	@Then("the account is open")
	public void theAccountIsOpen() {
		assertThat(lastResponse.status()).as(lastResponse.problem().toString()).isEqualTo(201);
		assertThat(lastAccount().getKind()).isEqualTo(AccountKindDto.REGULAR);
	}

	@Then("its balance is {word}")
	public void itsBalanceIs(String balance) {
		assertThat(lastAccount().getBalance()).isEqualTo(balance);
	}

	@Then("its available balance is {word}")
	public void itsAvailableBalanceIs(String balance) {
		assertThat(lastAccount().getAvailableBalance()).isEqualTo(balance);
	}

	@Then("its overdraft limit is {word}")
	public void itsOverdraftLimitIs(String limit) {
		assertThat(lastAccount().getOverdraftLimit()).isEqualTo(limit);
	}

	@Then("there is a settlement account for each of EUR, USD and JPY")
	public void settlementAccountsExist() {
		assertThat(Arrays.stream(api.settlementAccounts().body()).map(a -> a.getCurrency().getValue()))
				.containsExactlyInAnyOrder("EUR", "USD", "JPY");
	}

	@Then("settlement accounts have no overdraft limit")
	public void settlementAccountsHaveNoLimit() {
		assertThat(api.settlementAccounts().body()).allSatisfy(a -> {
			assertThat(a.getKind()).isEqualTo(AccountKindDto.SETTLEMENT);
			assertThat(a.getOverdraftLimit()).isNull();
		});
	}

	@When("a transaction {string} is recorded with entries:")
	public void aTransactionIsRecorded(String description, DataTable table) {
		recordTransaction(description, LedgerClient.key(), table);
	}

	@Given("a transaction {string} has been recorded with entries:")
	public void aTransactionHasBeenRecorded(String description, DataTable table) {
		Supplier<Response<?>> keptRequest = lastRequest;
		Response<?> keptResponse = lastResponse;
		recordTransaction(description, LedgerClient.key(), table);
		assertThat(lastResponse.status()).as(lastResponse.problem().toString()).isEqualTo(201);
		if (keptRequest != null) {
			lastRequest = keptRequest;
			lastResponse = keptResponse;
		}
	}

	@When("a transaction {string} is recorded with that key and entries:")
	public void aTransactionIsRecordedWithThatKey(String description, DataTable table) {
		recordTransaction(description, idempotencyKey, table);
	}

	@Then("the transaction is recorded")
	public void theTransactionIsRecorded() {
		assertThat(lastResponse.status()).as(lastResponse.problem().toString()).isEqualTo(201);
		assertThat(lastResponse.location()).startsWith("/v1/transactions/");
	}

	@Then("the balance of {string} is {word}")
	public void theBalanceOfIs(String alias, String balance) {
		assertThat(balanceOf(alias)).as(alias).isEqualTo(balance);
	}

	@Then("the balance of {string} changed by {word}")
	public void theBalanceOfChangedBy(String alias, String delta) {
		long before = minor(balancesBefore.get(alias));
		long after = minor(balanceOf(alias));
		assertThat(after - before).as(alias).isEqualTo(minor(delta));
	}

	@Then("the entries carry the balance after each of them")
	public void theEntriesCarryTheBalanceAfter() {
		TransactionDto tx = lastTransaction();
		Map<UUID, Long> running = new HashMap<>();
		for (EntryDto entry : tx.getEntries()) {
			String alias = aliasOf(entry.getAccountId());
			long before = running.getOrDefault(entry.getAccountId(), minor(balancesBefore.get(alias)));
			long after = entry.getDirection() == DirectionDto.CREDIT ? before + minor(entry.getAmount())
					: before - minor(entry.getAmount());
			assertThat(minor(entry.getBalanceAfter())).as(alias).isEqualTo(after);
			running.put(entry.getAccountId(), after);
		}
	}

	@Then("the {word} balances still add up to zero")
	public void theBalancesStillAddUpToZero(String currency) {
		Long total = jdbc.sql("SELECT COALESCE(SUM(balance_minor), 0) FROM accounts WHERE currency = :c")
				.param("c", currency).query(Long.class).single();
		assertThat(total).as(currency).isZero();
	}

	@Then("it is refused with {word} pointing at {string}")
	public void itIsRefusedWithPointingAt(String code, String pointer) {
		assertThat(lastResponse.status()).as(lastResponse.problem().toString()).isIn(400, 409, 422);
		assertThat(lastResponse.code(0)).isEqualTo(code);
		assertThat(lastResponse.pointer(0)).isEqualTo(pointer);
	}

	@Then("it is refused with {word}")
	public void itIsRefusedWith(String code) {
		assertThat(lastResponse.status()).as(lastResponse.problem().toString()).isIn(400, 409, 422);
		assertThat(lastResponse.code(0)).isEqualTo(code);
	}

	@Then("it is refused with these faults:")
	public void itIsRefusedWithTheseFaults(DataTable table) {
		assertThat(lastResponse.status()).isEqualTo(422);
		List<Tuple> expected = table.asMaps().stream()
				.map(row -> Tuple.tuple(row.get("code"), row.get("pointer"))).toList();
		assertThat(lastResponse.errors()).extracting(e -> e.get("code"), e -> e.get("pointer"))
				.containsExactlyElementsOf(expected);
	}

	@Then("nothing was recorded")
	public void nothingWasRecorded() {
		assertThat(counts()).isEqualTo(countsBefore);
	}

	@When("the transaction {string} is reversed")
	public void theTransactionIsReversed(String alias) {
		TransactionDto original = transactions.get(alias);
		snapshot();
		send(() -> api.reverse(original.getId(), LedgerClient.key(), null));
		if (lastResponse.isSuccess()) {
			transactions.put("reversal of " + alias, (TransactionDto) lastResponse.body());
		}
	}

	@Given("the transaction {string} has been reversed")
	public void theTransactionHasBeenReversed(String alias) {
		theTransactionIsReversed(alias);
		assertThat(lastResponse.status()).as(lastResponse.problem().toString()).isEqualTo(201);
	}

	@When("the reversal of {string} is reversed")
	public void theReversalOfIsReversed(String alias) {
		theTransactionIsReversed("reversal of " + alias);
	}

	@Then("a new transaction is recorded that reverses {string}")
	public void aNewTransactionReverses(String alias) {
		theTransactionIsRecorded();
		TransactionDto reversal = lastTransaction();
		assertThat(reversal.getId()).isNotEqualTo(transactions.get(alias).getId());
		assertThat(reversal.getReverses()).isEqualTo(transactions.get(alias).getId());
	}

	@Then("its entries are:")
	public void itsEntriesAre(DataTable table) {
		List<Tuple> expected = table.asMaps().stream()
				.map(row -> Tuple.tuple(accounts.get(row.get("account")), row.get("direction"), row.get("amount")))
				.toList();
		assertThat(lastTransaction().getEntries())
				.extracting(EntryDto::getAccountId, e -> e.getDirection().getValue(), EntryDto::getAmount)
				.containsExactlyElementsOf(expected);
	}

	@Then("the transaction {string} stays exactly as it was")
	public void theTransactionStaysExactlyAsItWas(String alias) {
		TransactionDto before = transactions.get(alias);
		TransactionDto now = api.getTransaction(before.getId()).body();
		assertThat(now.getEntries()).isEqualTo(before.getEntries());
		assertThat(now.getDescription()).isEqualTo(before.getDescription());
		assertThat(now.getRecordedAt()).isEqualTo(before.getRecordedAt());
		assertThat(now.getReversedBy()).isEqualTo(transactions.get("reversal of " + alias).getId());
	}

	@Given("an idempotency key")
	public void anIdempotencyKey() {
		idempotencyKey = LedgerClient.key();
	}

	@When("the same request is sent again with that key")
	public void theSameRequestIsSentAgain() {
		previousResponse = lastResponse;
		lastResponse = lastRequest.get();
	}

	@Then("both answers are identical")
	public void bothAnswersAreIdentical() {
		assertThat(lastResponse.status()).isEqualTo(previousResponse.status());
		assertThat(lastResponse.body()).isEqualTo(previousResponse.body());
		assertThat(lastResponse.location()).isEqualTo(previousResponse.location());
	}

	@Then("the answer still says the balance is {word}")
	public void theAnswerStillSaysTheBalanceIs(String balance) {
		assertThat(lastAccount().getBalance()).isEqualTo(balance);
	}

	@Then("only one account was opened by that key")
	public void onlyOneAccountWasOpenedByThatKey() {
		assertThat(lastAccount().getId()).isEqualTo(((AccountDto) previousResponse.body()).getId());
	}

	@Then("^\"([^\"]*)\" has (\\d+) entr(?:y|ies)$")
	public void hasEntries(String alias, int count) {
		Long entries = jdbc.sql("SELECT COUNT(*) FROM entries WHERE account_id = UUID_TO_BIN(:id)")
				.param("id", accounts.get(alias).toString()).query(Long.class).single();
		assertThat(entries).isEqualTo(count);
	}

	@When("a payment-settled message arrives for {string} of {word} {word}")
	public void aPaymentSettledMessageArrives(String alias, String amount, String currency) {
		String paymentId = "pay_" + UUID.randomUUID();
		sendPayment(paymentId, "evt_" + UUID.randomUUID(), accounts.get(alias).toString(), amount, currency);
		if (!currency.equals(currencies.get(alias))) {
			messageKeys.put("the wrong-currency message", paymentId);
		}
	}

	@Given("a payment-settled message has arrived for {string} of {word} {word}")
	public void aPaymentSettledMessageHasArrived(String alias, String amount, String currency) {
		aPaymentSettledMessageArrives(alias, amount, currency);
		withinSecondsTheBalanceOfIs(30, alias, amount);
	}

	@When("the same payment is redelivered with the same event id")
	public void redeliveredSameEventId() {
		kafka.send("payments.settled", lastPaymentId, lastPaymentMessage).join();
	}

	@When("the same payment is redelivered with a new event id")
	public void redeliveredNewEventId() {
		String message = lastPaymentMessage.replaceFirst("\"eventId\":\"[^\"]*\"", "\"eventId\":\"evt_" + UUID.randomUUID() + "\"");
		kafka.send("payments.settled", lastPaymentId, message).join();
	}

	@When("a marker payment of {word} {word} is processed")
	public void aMarkerPaymentIsProcessed(String amount, String currency) {
		anAccountIn("marker", currency);
		aPaymentSettledMessageHasArrived("marker", amount, currency);
	}

	@When("a malformed payment-settled message arrives")
	public void aMalformedMessageArrives() {
		String key = "malformed_" + UUID.randomUUID();
		kafka.send("payments.settled", key, "{\"eventId\": \"evt\", \"paymentId\": ").join();
		messageKeys.put("the malformed message", key);
	}

	@When("a payment-settled message arrives for an unknown account of {word} {word}")
	public void aMessageForAnUnknownAccount(String amount, String currency) {
		String paymentId = "pay_" + UUID.randomUUID();
		sendPayment(paymentId, "evt_" + UUID.randomUUID(), UUID.randomUUID().toString(), amount, currency);
		messageKeys.put("the unknown-account message", paymentId);
	}

	@Then("within {int} seconds the balance of {string} is {word}")
	public void withinSecondsTheBalanceOfIs(int seconds, String alias, String balance) {
		await().atMost(Duration.ofSeconds(seconds))
				.untilAsserted(() -> assertThat(balanceOf(alias)).as(alias).isEqualTo(balance));
	}

	@Then("the payment is marked as processed")
	public void thePaymentIsMarkedAsProcessed() {
		Long rows = jdbc.sql("SELECT COUNT(*) FROM processed_payments WHERE payment_id = :p")
				.param("p", lastPaymentId).query(Long.class).single();
		assertThat(rows).isEqualTo(1);
	}

	@Then("the dead-letter topic holds those three messages with reasons:")
	public void theDeadLetterTopicHolds(DataTable table) {
		Map<String, String> expected = new LinkedHashMap<>();
		table.asMaps().forEach(row -> expected.put(messageKeys.get(row.get("message")), row.get("reason")));
		Set<String> keys = expected.keySet();
		List<ConsumerRecord<String, String>> records = new KafkaReader(kafkaContainer.getBootstrapServers())
				.readFromBeginning("payments.settled.dlt", record -> keys.contains(record.key()),
						found -> found.stream().map(ConsumerRecord::key).collect(Collectors.toSet()).containsAll(keys),
						Duration.ofSeconds(30));
		Map<String, String> reasons = new HashMap<>();
		for (ConsumerRecord<String, String> record : records) {
			Header header = record.headers().lastHeader(KafkaConfig.ERROR_CODE_HEADER);
			reasons.put(record.key(), header == null ? null : new String(header.value(), StandardCharsets.UTF_8));
		}
		assertThat(reasons).containsAllEntriesOf(expected);
	}

	private void recordTransaction(String description, String key, DataTable table) {
		List<EntryRequestDto> entries = new ArrayList<>();
		for (Map<String, String> row : table.asMaps()) {
			entries.add(new EntryRequestDto().accountId(resolve(row.get("account")))
					.direction(DirectionDto.fromValue(row.get("direction"))).amount(row.get("amount")));
		}
		snapshot();
		EntryRequestDto[] array = entries.toArray(new EntryRequestDto[0]);
		send(() -> api.record(description, key, array));
		if (lastResponse.isSuccess()) {
			transactions.put(description, (TransactionDto) lastResponse.body());
		}
	}

	private void sendPayment(String paymentId, String eventId, String merchantAccountId, String amount, String currency) {
		lastPaymentId = paymentId;
		lastPaymentMessage = "{\"eventId\":\"" + eventId + "\",\"paymentId\":\"" + paymentId + "\",\"merchantAccountId\":\""
				+ merchantAccountId + "\",\"amount\":\"" + amount + "\",\"currency\":\"" + currency
				+ "\",\"occurredAt\":\"2026-01-01T00:00:00Z\"}";
		kafka.send("payments.settled", paymentId, lastPaymentMessage).join();
	}

	private void send(Supplier<Response<?>> request) {
		lastRequest = request;
		lastResponse = request.get();
	}

	private void rememberOpened(String currency) {
		if (lastResponse.isSuccess()) {
			accounts.put("the new account", ((AccountDto) lastResponse.body()).getId());
			currencies.put("the new account", currency);
		}
	}

	private UUID resolve(String alias) {
		if (alias.equals("an account that does not exist")) {
			return UUID.randomUUID();
		}
		UUID id = accounts.get(alias);
		assertThat(id).as("unknown account alias '%s'", alias).isNotNull();
		return id;
	}

	private String aliasOf(UUID id) {
		return accounts.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey).findFirst()
				.orElse(id.toString());
	}

	private void snapshot() {
		accounts.forEach((alias, id) -> balancesBefore.put(alias, balanceOf(alias)));
		countsBefore.clear();
		countsBefore.putAll(counts());
	}

	private Map<String, Long> counts() {
		Map<String, Long> counts = new HashMap<>();
		for (String table : List.of("transactions", "entries", "outbox_events")) {
			counts.put(table, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single());
		}
		return counts;
	}

	private String balanceOf(String alias) {
		return api.getAccount(accounts.get(alias)).body().getBalance();
	}

	private AccountDto lastAccount() {
		return (AccountDto) lastResponse.body();
	}

	private TransactionDto lastTransaction() {
		return (TransactionDto) lastResponse.body();
	}

	private static long minor(String decimal) {
		return Long.parseLong(decimal.replace(".", ""));
	}
}
