package com.toniflorea.ledger.adapter.inbound.rest;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountEntriesPageDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountEntryDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountKindDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.CurrencyDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.DirectionDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.application.port.EntryQueryRepository.AccountEntry;
import com.toniflorea.ledger.application.usecase.AccountQueries;
import com.toniflorea.ledger.application.usecase.TransactionQueries;
import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.money.Money;
import com.toniflorea.ledger.domain.transaction.Entry;
import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

final class RestMapper {

	private RestMapper() {
	}

	static AccountDto toDto(Account account) {
		return new AccountDto()
				.id(account.id().value())
				.currency(CurrencyDto.fromValue(account.currency().name()))
				.kind(AccountKindDto.fromValue(account.kind().name()))
				.overdraftLimit(account.overdraftLimit().asMoney().map(Money::toDecimalString).orElse(null))
				.balance(account.balance().toDecimalString())
				.availableBalance(account.availableBalance().map(Money::toDecimalString).orElse(null))
				.openedAt(utc(account.openedAt()));
	}

	static TransactionDto toDto(Transaction transaction) {
		return toDto(transaction, Optional.empty());
	}

	static TransactionDto toDto(TransactionQueries.Details details) {
		return toDto(details.transaction(), details.reversedBy());
	}

	private static TransactionDto toDto(Transaction transaction, Optional<TransactionId> reversedBy) {
		return new TransactionDto()
				.id(transaction.id().value())
				.description(transaction.description())
				.currency(CurrencyDto.fromValue(transaction.currency().name()))
				.recordedAt(utc(transaction.recordedAt()))
				.entries(transaction.entries().stream().map(RestMapper::toDto).toList())
				.reverses(transaction.reverses() == null ? null : transaction.reverses().value())
				.reversedBy(reversedBy.map(TransactionId::value).orElse(null));
	}

	static AccountEntriesPageDto toDto(AccountQueries.EntriesPage page) {
		return new AccountEntriesPageDto()
				.items(page.items().stream().map(RestMapper::toDto).toList())
				.nextCursor(page.nextCursor().orElse(null));
	}

	private static AccountEntryDto toDto(AccountEntry entry) {
		return new AccountEntryDto()
				.entryId(Long.toString(entry.id()))
				.transactionId(entry.transactionId().value())
				.description(entry.description())
				.direction(DirectionDto.fromValue(entry.direction().name()))
				.amount(entry.amount().toDecimalString())
				.balanceAfter(entry.balanceAfter().toDecimalString())
				.recordedAt(utc(entry.recordedAt()));
	}

	private static EntryDto toDto(Entry entry) {
		return new EntryDto()
				.accountId(entry.accountId().value())
				.direction(DirectionDto.fromValue(entry.direction().name()))
				.amount(entry.amount().toDecimalString())
				.balanceAfter(entry.balanceAfter().toDecimalString());
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
