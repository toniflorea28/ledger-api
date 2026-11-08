package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.port.TransactionRepository;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.Entry;
import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTransactionRepository implements TransactionRepository {

	private record Header(TransactionId id, String description, Currency currency, Instant recordedAt,
			TransactionId reverses) {
	}

	private final JdbcClient jdbc;

	public JdbcTransactionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(Transaction transaction) {
		jdbc.sql("""
				INSERT INTO transactions (id, currency, description, recorded_at, reverses_id)
				VALUES (:id, :currency, :description, :recordedAt, :reverses)
				""")
				.param("id", Uuids.toBytes(transaction.id().value()))
				.param("currency", transaction.currency().name())
				.param("description", transaction.description())
				.param("recordedAt", Timestamp.from(transaction.recordedAt()))
				.param("reverses", transaction.reverses() == null ? null : Uuids.toBytes(transaction.reverses().value()))
				.update();
		List<Entry> entries = transaction.entries();
		for (int position = 0; position < entries.size(); position++) {
			Entry entry = entries.get(position);
			jdbc.sql("""
					INSERT INTO entries (transaction_id, position, account_id, direction, amount_minor,
					                     balance_after_minor, recorded_at)
					VALUES (:transactionId, :position, :accountId, :direction, :amount, :balanceAfter, :recordedAt)
					""")
					.param("transactionId", Uuids.toBytes(transaction.id().value()))
					.param("position", position)
					.param("accountId", Uuids.toBytes(entry.accountId().value()))
					.param("direction", entry.direction().name())
					.param("amount", entry.amount().minorUnits())
					.param("balanceAfter", entry.balanceAfter().minorUnits())
					.param("recordedAt", Timestamp.from(transaction.recordedAt()))
					.update();
		}
	}

	@Override
	public Optional<Transaction> find(TransactionId id) {
		return header(id, "").map(this::withEntries);
	}

	@Override
	public Optional<Transaction> lock(TransactionId id) {
		return header(id, " FOR UPDATE").map(this::withEntries);
	}

	@Override
	public Optional<TransactionId> findReversalOf(TransactionId id) {
		return jdbc.sql("SELECT id FROM transactions WHERE reverses_id = :id")
				.param("id", Uuids.toBytes(id.value()))
				.query((rs, i) -> TransactionId.of(Uuids.fromBytes(rs.getBytes("id"))))
				.optional();
	}

	private Optional<Header> header(TransactionId id, String suffix) {
		return jdbc.sql("SELECT id, currency, description, recorded_at, reverses_id FROM transactions WHERE id = :id"
				+ suffix)
				.param("id", Uuids.toBytes(id.value()))
				.query(this::mapHeader)
				.optional();
	}

	private Transaction withEntries(Header header) {
		List<Entry> entries = jdbc.sql("""
				SELECT account_id, direction, amount_minor, balance_after_minor
				FROM entries WHERE transaction_id = :id ORDER BY position
				""")
				.param("id", Uuids.toBytes(header.id().value()))
				.query((rs, i) -> new Entry(AccountId.of(Uuids.fromBytes(rs.getBytes("account_id"))),
						Direction.valueOf(rs.getString("direction")),
						Money.ofMinor(rs.getLong("amount_minor"), header.currency()),
						Money.ofMinor(rs.getLong("balance_after_minor"), header.currency())))
				.list();
		return new Transaction(header.id(), header.description(), header.currency(), header.recordedAt(), entries,
				header.reverses());
	}

	private Header mapHeader(ResultSet rs, int rowNum) throws SQLException {
		byte[] reverses = rs.getBytes("reverses_id");
		return new Header(TransactionId.of(Uuids.fromBytes(rs.getBytes("id"))), rs.getString("description"),
				Currency.valueOf(rs.getString("currency")), rs.getTimestamp("recorded_at").toInstant(),
				reverses == null ? null : TransactionId.of(Uuids.fromBytes(reverses)));
	}
}
