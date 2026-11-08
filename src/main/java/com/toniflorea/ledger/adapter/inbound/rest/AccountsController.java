package com.toniflorea.ledger.adapter.inbound.rest;

import com.toniflorea.ledger.adapter.inbound.rest.api.AccountsApi;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountEntriesPageDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.BalanceAtDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.OpenAccountRequestDto;
import com.toniflorea.ledger.application.usecase.AccountQueries;
import com.toniflorea.ledger.application.usecase.OpenAccount;
import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.money.Money;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountsController implements AccountsApi {

	private final OpenAccount openAccount;
	private final AccountQueries queries;
	private final IdempotentRequests idempotent;

	public AccountsController(OpenAccount openAccount, AccountQueries queries, IdempotentRequests idempotent) {
		this.openAccount = openAccount;
		this.queries = queries;
		this.idempotent = idempotent;
	}

	@Override
	public ResponseEntity<AccountDto> openAccount(String idempotencyKey, OpenAccountRequestDto request) {
		return idempotent.execute("openAccount", idempotencyKey, AccountsApi.PATH_OPEN_ACCOUNT, request,
				AccountDto.class, () -> {
					Account account = openAccount.open(new OpenAccount.Command(request.getCurrency(),
							Optional.ofNullable(request.getOverdraftLimit())));
					URI location = URI.create(AccountsApi.PATH_GET_ACCOUNT.replace("{accountId}", account.id().toString()));
					return ResponseEntity.created(location).body(RestMapper.toDto(account));
				});
	}

	@Override
	public ResponseEntity<AccountDto> getAccount(UUID accountId) {
		return ResponseEntity.ok(RestMapper.toDto(queries.get(AccountId.of(accountId))));
	}

	@Override
	public ResponseEntity<List<AccountDto>> listSettlementAccounts() {
		return ResponseEntity.ok(queries.settlementAccounts().stream().map(RestMapper::toDto).toList());
	}

	@Override
	public ResponseEntity<AccountEntriesPageDto> listAccountEntries(UUID accountId, String cursor, Integer limit) {
		AccountQueries.EntriesPage page = queries.entries(AccountId.of(accountId), Optional.ofNullable(cursor),
				limit == null ? 50 : limit);
		return ResponseEntity.ok(RestMapper.toDto(page));
	}

	@Override
	public ResponseEntity<BalanceAtDto> getAccountBalanceAt(UUID accountId, OffsetDateTime at) {
		Money balance = queries.balanceAt(AccountId.of(accountId), at.toInstant());
		return ResponseEntity.ok(new BalanceAtDto().accountId(accountId).at(at.withOffsetSameInstant(ZoneOffset.UTC))
				.balance(balance.toDecimalString()));
	}
}
