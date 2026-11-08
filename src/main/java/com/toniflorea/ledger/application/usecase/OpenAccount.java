package com.toniflorea.ledger.application.usecase;

import com.toniflorea.ledger.domain.account.Account;
import java.util.Optional;

public interface OpenAccount {

	Account open(Command command);

	record Command(String currencyCode, Optional<String> overdraftLimit) {
	}
}
