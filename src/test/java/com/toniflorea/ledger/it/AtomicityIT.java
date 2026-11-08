package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static com.toniflorea.ledger.support.LedgerClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.support.IntegrationTest;
import com.toniflorea.ledger.support.LedgerClient.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AtomicityIT extends IntegrationTest {

	@Test
	void aRefusedTransactionLeavesNothingBehind() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		UUID payouts = openAccount("EUR");
		UUID fees = openAccount("EUR");
		record("settle", debit(settlement, "125.50"), credit(merchant, "125.50"));
		Snapshot before = snapshot(settlement, merchant, payouts, fees);

		Response<TransactionDto> overdraft = api.record("payout", key(), debit(merchant, "100.00"),
				credit(payouts, "98.50"), credit(fees, "1.50"), debit(merchant, "50.00"), credit(fees, "50.00"));
		assertThat(overdraft.status()).isEqualTo(422);
		assertThat(overdraft.code(0)).isEqualTo("OVERDRAFT_EXCEEDED");

		Response<TransactionDto> unknown = api.record("payout", key(), debit(merchant, "10.00"),
				credit(UUID.randomUUID(), "10.00"));
		assertThat(unknown.status()).isEqualTo(422);

		assertThat(snapshot(settlement, merchant, payouts, fees)).isEqualTo(before);
		assertLedgerIntegrity();
	}

	@Test
	void aRecordedTransactionWritesItsEntriesBalancesAndOutboxRowTogether() {
		UUID settlement = settlementAccount("EUR");
		UUID merchant = openAccount("EUR");
		long transactions = count("transactions");
		long entries = count("entries");
		long outbox = count("outbox_events");

		TransactionDto tx = record("settle", debit(settlement, "10.00"), credit(merchant, "10.00"));

		assertThat(count("transactions")).isEqualTo(transactions + 1);
		assertThat(count("entries")).isEqualTo(entries + 2);
		assertThat(count("outbox_events")).isEqualTo(outbox + 1);
		Map<String, Object> event = jdbc.sql("""
				SELECT event_type, partition_key, published_at, JSON_UNQUOTE(JSON_EXTRACT(payload, '$.transaction.id')) AS tx_id,
				       JSON_UNQUOTE(JSON_EXTRACT(payload, '$.transaction.entries[1].balanceAfter')) AS balance_after
				FROM outbox_events WHERE aggregate_id = UUID_TO_BIN(:id)
				""").param("id", tx.getId().toString()).query().singleRow();
		assertThat(event).containsEntry("event_type", "ledger.transaction-recorded")
				.containsEntry("partition_key", "EUR")
				.containsEntry("tx_id", tx.getId().toString())
				.containsEntry("balance_after", "10.00");
		assertThat(event.get("published_at")).isNull();
	}

	private record Snapshot(long transactions, long entries, long outbox, Map<UUID, String> balances) {
	}

	private Snapshot snapshot(UUID... accounts) {
		Map<UUID, String> balances = new java.util.HashMap<>();
		for (UUID account : accounts) {
			balances.put(account, balanceOf(account));
		}
		return new Snapshot(count("transactions"), count("entries"), count("outbox_events"), balances);
	}
}
