package com.toniflorea.ledger.infrastructure;

import com.toniflorea.ledger.application.idempotency.IdempotencyStore;
import com.toniflorea.ledger.application.idempotency.IdempotentExecutor;
import com.toniflorea.ledger.application.port.AccountRepository;
import com.toniflorea.ledger.application.port.IdGenerator;
import com.toniflorea.ledger.application.port.LedgerClock;
import com.toniflorea.ledger.application.port.OutboxRepository;
import com.toniflorea.ledger.application.port.TransactionRepository;
import com.toniflorea.ledger.application.service.AccountService;
import com.toniflorea.ledger.application.service.PostingService;
import com.toniflorea.ledger.application.service.TransactionService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
@EnableScheduling
public class ApplicationConfig {

	@Bean
	TransactionOperations transactionOperations(PlatformTransactionManager transactionManager) {
		return new TransactionTemplate(transactionManager);
	}

	@Bean
	IdempotentExecutor idempotentExecutor(IdempotencyStore store, TransactionOperations tx,
			PlatformTransactionManager transactionManager, LedgerClock clock, LedgerProperties properties) {
		TransactionTemplate nested = new TransactionTemplate(transactionManager);
		nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
		return new IdempotentExecutor(store, tx, nested, clock, properties.idempotency().ttl());
	}

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
