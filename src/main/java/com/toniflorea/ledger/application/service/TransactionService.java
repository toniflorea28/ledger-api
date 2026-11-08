package com.toniflorea.ledger.application.service;

import com.toniflorea.ledger.application.port.TransactionRepository;
import com.toniflorea.ledger.application.usecase.RecordTransaction;
import com.toniflorea.ledger.application.usecase.ReverseTransaction;
import com.toniflorea.ledger.application.usecase.TransactionQueries;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerError;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.DecimalAmount;
import com.toniflorea.ledger.domain.transaction.EntryDraft;
import com.toniflorea.ledger.domain.transaction.Reversal;
import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionDraft;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class TransactionService implements RecordTransaction, ReverseTransaction, TransactionQueries {

	private final TransactionRepository transactions;
	private final PostingService posting;

	public TransactionService(TransactionRepository transactions, PostingService posting) {
		this.transactions = transactions;
		this.posting = posting;
	}

	@Override
	@Transactional
	public Transaction record(RecordTransaction.Command command) {
		List<LedgerError> errors = new ArrayList<>();
		List<EntryDraft> entries = new ArrayList<>();
		for (int i = 0; i < command.entries().size(); i++) {
			Entry entry = command.entries().get(i);
			try {
				entries.add(new EntryDraft(entry.accountId(), entry.direction(),
						DecimalAmount.parse(entry.amount(), "/entries/" + i + "/amount")));
			}
			catch (LedgerException e) {
				errors.addAll(e.errors());
			}
		}
		if (!errors.isEmpty()) {
			throw new LedgerException(errors);
		}
		return posting.post(TransactionDraft.of(command.description(), entries));
	}

	@Override
	@Transactional
	public Transaction reverse(ReverseTransaction.Command command) {
		Transaction original = transactions.lock(command.transactionId())
				.orElseThrow(() -> notFound(command.transactionId()));
		Optional<TransactionId> existing = transactions.findReversalOf(original.id());
		return posting.post(Reversal.draft(original, existing, command.description()));
	}

	@Override
	@Transactional(readOnly = true)
	public Details get(TransactionId id) {
		Transaction transaction = transactions.find(id).orElseThrow(() -> notFound(id));
		return new Details(transaction, transactions.findReversalOf(id));
	}

	private static LedgerException notFound(TransactionId id) {
		return LedgerException.of(ErrorCode.NOT_FOUND, "transaction " + id + " does not exist");
	}
}
