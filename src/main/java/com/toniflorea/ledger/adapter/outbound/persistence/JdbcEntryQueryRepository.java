package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.port.EntryQueryRepository;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEntryQueryRepository implements EntryQueryRepository {

	private final JdbcClient jdbc;

	public JdbcEntryQueryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<AccountEntry> findNewestFirst(AccountId accountId, Currency currency, Optional<Long> beforeId,
			int limit) {
		return jdbc.sql("""
				SELECT e.id, e.transaction_id, t.description, e.direction, e.amount_minor, e.balance_after_minor,
				       e.recorded_at
				FROM entries e
				JOIN transactions t ON t.id = e.transaction_id
				WHERE e.account_id = :accountId AND (:before IS NULL OR e.id < :before)
				ORDER BY e.id DESC
				LIMIT :limit
				""")
				.param("accountId", Uuids.toBytes(accountId.value()))
				.param("before", beforeId.orElse(null))
				.param("limit", limit)
				.query((rs, i) -> new AccountEntry(rs.getLong("id"),
						TransactionId.of(Uuids.fromBytes(rs.getBytes("transaction_id"))), rs.getString("description"),
						Direction.valueOf(rs.getString("direction")), Money.ofMinor(rs.getLong("amount_minor"), currency),
						Money.ofMinor(rs.getLong("balance_after_minor"), currency),
						rs.getTimestamp("recorded_at").toInstant()))
				.list();
	}

	@Override
	public Optional<Money> balanceAt(AccountId accountId, Currency currency, Instant at) {
		return jdbc.sql("""
				SELECT balance_after_minor FROM entries
				WHERE account_id = :accountId AND recorded_at <= :at
				ORDER BY recorded_at DESC, id DESC
				LIMIT 1
				""")
				.param("accountId", Uuids.toBytes(accountId.value()))
				.param("at", Timestamp.from(at))
				.query((rs, i) -> Money.ofMinor(rs.getLong("balance_after_minor"), currency))
				.optional();
	}
}
