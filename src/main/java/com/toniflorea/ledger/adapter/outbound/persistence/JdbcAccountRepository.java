package com.toniflorea.ledger.adapter.outbound.persistence;

import com.toniflorea.ledger.application.port.AccountRepository;
import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.account.AccountKind;
import com.toniflorea.ledger.domain.account.OverdraftLimit;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {

	private static final String COLUMNS = "id, currency, kind, overdraft_limit_minor, balance_minor, opened_at";

	private final JdbcClient jdbc;

	public JdbcAccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<Account> find(AccountId id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id")
				.param("id", Uuids.toBytes(id.value()))
				.query(this::map)
				.optional();
	}

	@Override
	public Map<AccountId, Account> lockAll(SortedSet<AccountId> ids) {
		Map<AccountId, Account> locked = new LinkedHashMap<>();
		for (AccountId id : ids) {
			jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id FOR UPDATE")
					.param("id", Uuids.toBytes(id.value()))
					.query(this::map)
					.optional()
					.ifPresent(account -> locked.put(id, account));
		}
		return locked;
	}

	@Override
	public void insert(Account account) {
		jdbc.sql("""
				INSERT INTO accounts (id, currency, kind, overdraft_limit_minor, balance_minor, opened_at)
				VALUES (:id, :currency, :kind, :limit, :balance, :openedAt)
				""")
				.param("id", Uuids.toBytes(account.id().value()))
				.param("currency", account.currency().name())
				.param("kind", account.kind().name())
				.param("limit", account.overdraftLimit().asMoney().map(Money::minorUnits).orElse(null))
				.param("balance", account.balance().minorUnits())
				.param("openedAt", Timestamp.from(account.openedAt()))
				.update();
	}

	@Override
	public void updateBalance(Account account) {
		int updated = jdbc.sql("UPDATE accounts SET balance_minor = :balance WHERE id = :id")
				.param("balance", account.balance().minorUnits())
				.param("id", Uuids.toBytes(account.id().value()))
				.update();
		if (updated != 1) {
			throw new IllegalStateException("account " + account.id() + " vanished while locked");
		}
	}

	@Override
	public List<Account> findSettlementAccounts() {
		return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE kind = 'SETTLEMENT' ORDER BY currency")
				.query(this::map)
				.list();
	}

	@Override
	public Optional<Account> findSettlementAccount(Currency currency) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE kind = 'SETTLEMENT' AND currency = :currency")
				.param("currency", currency.name())
				.query(this::map)
				.optional();
	}

	private Account map(ResultSet rs, int rowNum) throws SQLException {
		Currency currency = Currency.valueOf(rs.getString("currency"));
		long limit = rs.getLong("overdraft_limit_minor");
		OverdraftLimit overdraftLimit = rs.wasNull() ? OverdraftLimit.unlimited()
				: new OverdraftLimit(Money.ofMinor(limit, currency));
		return new Account(AccountId.of(Uuids.fromBytes(rs.getBytes("id"))), currency,
				AccountKind.valueOf(rs.getString("kind")), overdraftLimit,
				Money.ofMinor(rs.getLong("balance_minor"), currency), rs.getTimestamp("opened_at").toInstant());
	}
}
