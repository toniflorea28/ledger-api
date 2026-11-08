package com.toniflorea.ledger.adapter.inbound.rest;

import com.toniflorea.ledger.adapter.inbound.rest.api.TransactionsApi;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.RecordTransactionRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.ReverseTransactionRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import com.toniflorea.ledger.application.usecase.RecordTransaction;
import com.toniflorea.ledger.application.usecase.ReverseTransaction;
import com.toniflorea.ledger.application.usecase.TransactionQueries;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransactionsController implements TransactionsApi {

	private final RecordTransaction recordTransaction;
	private final ReverseTransaction reverseTransaction;
	private final TransactionQueries queries;

	public TransactionsController(RecordTransaction recordTransaction, ReverseTransaction reverseTransaction,
			TransactionQueries queries) {
		this.recordTransaction = recordTransaction;
		this.reverseTransaction = reverseTransaction;
		this.queries = queries;
	}

	@Override
	public ResponseEntity<TransactionDto> recordTransaction(String idempotencyKey,
			RecordTransactionRequestDto request) {
		List<RecordTransaction.Entry> entries = request.getEntries().stream().map(this::toEntry).toList();
		Transaction transaction = recordTransaction
				.record(new RecordTransaction.Command(request.getDescription(), entries));
		return created(transaction);
	}

	@Override
	public ResponseEntity<TransactionDto> getTransaction(UUID transactionId) {
		return ResponseEntity.ok(RestMapper.toDto(queries.get(TransactionId.of(transactionId))));
	}

	@Override
	public ResponseEntity<TransactionDto> reverseTransaction(UUID transactionId, String idempotencyKey,
			ReverseTransactionRequestDto request) {
		Optional<String> description = Optional.ofNullable(request).map(ReverseTransactionRequestDto::getDescription);
		Transaction reversal = reverseTransaction
				.reverse(new ReverseTransaction.Command(TransactionId.of(transactionId), description));
		return created(reversal);
	}

	private RecordTransaction.Entry toEntry(EntryRequestDto dto) {
		return new RecordTransaction.Entry(AccountId.of(dto.getAccountId()),
				Direction.valueOf(dto.getDirection().getValue()), dto.getAmount());
	}

	private static ResponseEntity<TransactionDto> created(Transaction transaction) {
		URI location = URI.create(
				TransactionsApi.PATH_GET_TRANSACTION.replace("{transactionId}", transaction.id().toString()));
		return ResponseEntity.created(location).body(RestMapper.toDto(transaction));
	}
}
