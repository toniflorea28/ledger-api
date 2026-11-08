package com.toniflorea.ledger.it;

import static com.toniflorea.ledger.support.LedgerClient.credit;
import static com.toniflorea.ledger.support.LedgerClient.debit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

class ImmutabilityIT extends IntegrationTest {

	@Test
	void theDatabaseRefusesToChangeOrDeleteTransactionsAndEntries() {
		UUID merchant = openAccount("EUR");
		TransactionDto tx = record("settle", debit(settlementAccount("EUR"), "5.00"), credit(merchant, "5.00"));
		String id = tx.getId().toString();

		assertThatThrownBy(() -> jdbc.sql("UPDATE transactions SET description = 'tampered' WHERE id = UUID_TO_BIN(:id)")
				.param("id", id).update())
				.isInstanceOf(DataAccessException.class).hasMessageContaining("transactions are immutable");
		assertThatThrownBy(() -> jdbc.sql("UPDATE entries SET amount_minor = 1 WHERE transaction_id = UUID_TO_BIN(:id)")
				.param("id", id).update())
				.isInstanceOf(DataAccessException.class).hasMessageContaining("entries are immutable");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM entries WHERE transaction_id = UUID_TO_BIN(:id)")
				.param("id", id).update())
				.isInstanceOf(DataAccessException.class).hasMessageContaining("entries are immutable");
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM transactions WHERE id = UUID_TO_BIN(:id)")
				.param("id", id).update())
				.isInstanceOf(DataAccessException.class).hasMessageContaining("transactions are immutable");

		assertThat(api.getTransaction(tx.getId()).body()).isEqualTo(tx);
	}
}
