package com.toniflorea.ledger.application.service;

import com.toniflorea.ledger.application.port.AccountRepository;
import com.toniflorea.ledger.application.port.LedgerClock;
import com.toniflorea.ledger.application.port.ProcessedPaymentStore;
import com.toniflorea.ledger.application.usecase.RecordSettledPayment;
import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import com.toniflorea.ledger.domain.money.Currency;
import com.toniflorea.ledger.domain.money.DecimalAmount;
import com.toniflorea.ledger.domain.transaction.Direction;
import com.toniflorea.ledger.domain.transaction.EntryDraft;
import com.toniflorea.ledger.domain.transaction.Transaction;
import com.toniflorea.ledger.domain.transaction.TransactionDraft;
import com.toniflorea.ledger.domain.transaction.TransactionId;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * One transaction per paymentId, however often and under whichever eventId the
 * message arrives. The processed-payment row is written in the posting's own
 * transaction, so a redelivery either finds it or collides with it on the primary
 * key; in both cases nothing is recorded twice.
 */
public class SettlementService implements RecordSettledPayment {

	private final AccountRepository accounts;
	private final ProcessedPaymentStore processedPayments;
	private final PostingService posting;
	private final LedgerClock clock;
	private final TransactionOperations tx;

	public SettlementService(AccountRepository accounts, ProcessedPaymentStore processedPayments,
			PostingService posting, LedgerClock clock, TransactionOperations tx) {
		this.accounts = accounts;
		this.processedPayments = processedPayments;
		this.posting = posting;
		this.clock = clock;
		this.tx = tx;
	}

	@Override
	public Outcome record(Command command) {
		try {
			return tx.execute(status -> {
				Optional<TransactionId> existing = processedPayments.find(command.paymentId());
				if (existing.isPresent()) {
					return new Outcome(existing.get(), true);
				}
				Transaction transaction = posting.post(draft(command));
				processedPayments.insert(command.paymentId(), command.eventId(), transaction.id(), clock.now());
				return new Outcome(transaction.id(), false);
			});
		}
		catch (ProcessedPaymentStore.AlreadyProcessed raced) {
			TransactionId existing = tx.execute(status -> processedPayments.find(command.paymentId())).orElseThrow();
			return new Outcome(existing, true);
		}
	}

	private TransactionDraft draft(Command command) {
		Currency currency = Currency.parse(command.currency(), "/currency");
		Account settlement = accounts.findSettlementAccount(currency)
				.orElseThrow(() -> new IllegalStateException("no settlement account for " + currency));
		AccountId merchant = parseAccountId(command.merchantAccountId());
		DecimalAmount amount = DecimalAmount.parse(command.amount(), "/amount");
		return TransactionDraft.of("Payment " + command.paymentId() + " settled", List.of(
				new EntryDraft(settlement.id(), Direction.DEBIT, amount),
				new EntryDraft(merchant, Direction.CREDIT, amount)));
	}

	private static AccountId parseAccountId(String text) {
		try {
			return AccountId.of(text);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw LedgerException.of(ErrorCode.UNKNOWN_ACCOUNT, "/merchantAccountId",
					"'" + text + "' is not an account id");
		}
	}
}
