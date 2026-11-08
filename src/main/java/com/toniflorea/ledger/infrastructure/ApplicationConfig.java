package com.toniflorea.ledger.infrastructure;

import com.toniflorea.ledger.application.port.AccountRepository;
import com.toniflorea.ledger.application.port.IdGenerator;
import com.toniflorea.ledger.application.port.LedgerClock;
import com.toniflorea.ledger.application.port.OutboxRepository;
import com.toniflorea.ledger.application.port.TransactionRepository;
import com.toniflorea.ledger.application.service.AccountService;
import com.toniflorea.ledger.application.service.PostingService;
import com.toniflorea.ledger.application.service.TransactionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfig {

	@Bean
	PostingService postingService(AccountRepository accounts, TransactionRepository transactions,
			OutboxRepository outbox, IdGenerator ids, LedgerClock clock) {
		return new PostingService(accounts, transactions, outbox, ids, clock);
	}

	@Bean
	AccountService accountService(AccountRepository accounts, IdGenerator ids, LedgerClock clock) {
		return new AccountService(accounts, ids, clock);
	}

	@Bean
	TransactionService transactionService(TransactionRepository transactions, PostingService posting) {
		return new TransactionService(transactions, posting);
	}
}
