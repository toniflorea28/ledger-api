Feature: Idempotent requests
  A client that does not get an answer can safely send the same request again. The
  request takes effect at most once and the retry gets the same answer the first attempt
  would have given. The ledger never mistakes a different request for a retry.

  Background:
    Given an account "Merchant 42" in EUR
    And an account "Payouts EUR" in EUR

  Scenario: Retrying a recorded transaction records it once
    Given an idempotency key
    When a transaction "settle" is recorded with that key and entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 125.50 |
      | Merchant 42    | CREDIT    | 125.50 |
    And the same request is sent again with that key
    Then both answers are identical
    And the balance of "Merchant 42" is 125.50
    And "Merchant 42" has 1 entry

  Scenario: Retrying an opened account returns the first answer even after the balance changed
    Given an idempotency key
    When an account is opened in EUR with that key
    And a transaction "fund" has been recorded with entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 7.00   |
      | the new account| CREDIT    | 7.00   |
    And the same request is sent again with that key
    Then the answer still says the balance is 0.00
    And only one account was opened by that key

  Scenario: The same key with a different request is refused and records nothing
    Given an idempotency key
    When a transaction "settle" is recorded with that key and entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 10.00  |
      | Merchant 42    | CREDIT    | 10.00  |
    And a transaction "settle" is recorded with that key and entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 11.00  |
      | Merchant 42    | CREDIT    | 11.00  |
    Then it is refused with IDEMPOTENCY_KEY_REUSED
    And the balance of "Merchant 42" is 10.00

  Scenario: A refusal is remembered even after the world changes
    Given an idempotency key
    When a transaction "payout" is recorded with that key and entries:
      | account     | direction | amount |
      | Merchant 42 | DEBIT     | 10.00  |
      | Payouts EUR | CREDIT    | 10.00  |
    Then it is refused with OVERDRAFT_EXCEEDED
    When a transaction "fund" has been recorded with entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 10.00  |
      | Merchant 42    | CREDIT    | 10.00  |
    And the same request is sent again with that key
    Then it is refused with OVERDRAFT_EXCEEDED
    And the balance of "Merchant 42" is 10.00
