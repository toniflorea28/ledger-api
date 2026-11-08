package com.toniflorea.ledger.application.usecase;

import com.toniflorea.ledger.domain.account.Account;
import com.toniflorea.ledger.domain.account.AccountId;
import java.util.List;

public interface AccountQueries {

	Account get(AccountId id);

	List<Account> settlementAccounts();
}
