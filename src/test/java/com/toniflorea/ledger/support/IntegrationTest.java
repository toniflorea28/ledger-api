package com.toniflorea.ledger.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.toniflorea.ledger.TestcontainersConfiguration;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

	@LocalServerPort
	protected int port;

	@Autowired
	protected JdbcClient jdbc;

	@Autowired
	protected JsonMapper json;

	protected LedgerClient api;

	@BeforeEach
	void createClient() {
		api = new LedgerClient("http://localhost:" + port, json);
	}

	protected UUID settlementAccount(String currency) {
		return Arrays.stream(api.settlementAccounts().body())
				.filter(a -> a.getCurrency().getValue().equals(currency))
				.findFirst().orElseThrow().getId();
	}

	protected UUID openAccount(String currency) {
		return openAccount(currency, null);
	}

	protected UUID openAccount(String currency, String overdraftLimit) {
		LedgerClient.Response<AccountDto> response = api.openAccount(currency, overdraftLimit, LedgerClient.key());
		assertThat(response.status()).as("open account: %s", response.problem()).isEqualTo(201);
		return response.body().getId();
	}

	protected TransactionDto record(String description, EntryRequestDto... entries) {
		LedgerClient.Response<TransactionDto> response = api.record(description, LedgerClient.key(), entries);
		assertThat(response.status()).as("record: %s", response.problem()).isEqualTo(201);
		return response.body();
	}

	protected String balanceOf(UUID accountId) {
		return api.getAccount(accountId).body().getBalance();
	}

	protected long count(String table) {
		return jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single();
	}

	protected void assertLedgerIntegrity() {
		List<Map<String, Object>> sums = jdbc
				.sql("SELECT currency, SUM(balance_minor) AS total FROM accounts GROUP BY currency")
				.query().listOfRows();
		assertThat(sums).as("per-currency balances must sum to zero").allSatisfy(
				row -> assertThat(((Number) row.get("total")).longValue()).as(row.get("currency").toString())
						.isZero());

		List<Map<String, Object>> drift = jdbc.sql("""
				SELECT BIN_TO_UUID(a.id) AS id, a.balance_minor, COALESCE(e.total, 0) AS from_entries
				FROM accounts a
				LEFT JOIN (SELECT account_id,
				                  SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) AS total
				           FROM entries GROUP BY account_id) e ON e.account_id = a.id
				WHERE a.balance_minor <> COALESCE(e.total, 0)
				""").query().listOfRows();
		assertThat(drift).as("every balance must equal the sum of its entries").isEmpty();
	}
}
