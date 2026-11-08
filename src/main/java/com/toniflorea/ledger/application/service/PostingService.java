package com.toniflorea.ledger.application.service;

import com.toniflorea.ledger.application.event.TransactionRecordedEvent;
import com.toniflorea.ledger.application.port.AccountRepository;
import com.toniflorea.ledger.application.port.IdGenerator;
import com.toniflorea.ledger.application.port.LedgerClock;
import com.toniflorea.ledger.application.port.OutboxRepository;
import com.toniflorea.ledger.application.port.TraceContext;
import com.toniflorea.ledger.application.port.TransactionRepository;
import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.transaction.EntryDraft;
import com.toniflorea.ledger.domain.transaction.Posting;
import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionDraft;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.time.Instant;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class PostingService {

	private static final Logger log = LoggerFactory.getLogger(PostingService.class);

	private final AccountRepository accounts;
	private final TransactionRepository transactions;
	private final OutboxRepository outbox;
	private final IdGenerator ids;
	private final LedgerClock clock;
	private final TraceContext trace;

	public PostingService(AccountRepository accounts, TransactionRepository transactions, OutboxRepository outbox,
			IdGenerator ids, LedgerClock clock, TraceContext trace) {
		this.accounts = accounts;
		this.transactions = transactions;
		this.outbox = outbox;
		this.ids = ids;
		this.clock = clock;
		this.trace = trace;
	}

	/**
	 * Locks the accounts in ascending id order so two postings touching the same
	 * accounts always queue in the same direction and cannot deadlock, then
	 * validates, writes the transaction, the new balances and the outbox row in
	 * the caller's transaction.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Transaction post(TransactionDraft draft) {
		SortedSet<AccountId> accountIds = new TreeSet<>();
		for (EntryDraft entry : draft.entries()) {
			accountIds.add(entry.accountId());
		}
		Map<AccountId, Account> locked = accounts.lockAll(accountIds);
		Instant now = clock.now();

		Posting.Result result = Posting.post(draft, TransactionId.of(ids.next()), now, locked);

		transactions.insert(result.transaction());
		for (Account account : result.accounts()) {
			accounts.updateBalance(account);
		}
		outbox.append(new TransactionRecordedEvent(ids.next(), now, result.transaction(), trace.currentHeaders()));
		log.info("recorded transaction {} in {} with {} entries{}", result.transaction().id(), result.transaction().currency(),
				result.transaction().entries().size(),
				draft.isReversal() ? " reversing " + draft.reverses() : "");
		return result.transaction();
	}
}
