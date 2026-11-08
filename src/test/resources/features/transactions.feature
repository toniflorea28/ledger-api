Feature: Recording transactions
  A transaction is two or more entries (debits and credits) that always balance. It is
  recorded all or nothing, and once recorded it is never changed. These scenarios are the
  worked examples of the challenge: all accounts start at zero, Settlement EUR and
  Settlement JPY are the ledger's own accounts with no overdraft limit, every other
  account has an overdraft limit of zero.

  Background:
    Given an account "Merchant 42" in EUR
    And an account "Payouts EUR" in EUR
    And an account "Fees EUR" in EUR
    And an account "Merchant 7" in JPY
    And an account "Payouts JPY" in JPY

  Scenario: Example 1 - a payment of 125.50 EUR settles for Merchant 42
    When a transaction "payment settled" is recorded with entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 125.50 |
      | Merchant 42    | CREDIT    | 125.50 |
    Then the transaction is recorded
    And the balance of "Merchant 42" is 125.50
    And the balance of "Settlement EUR" changed by -125.50
    And the entries carry the balance after each of them
    And the EUR balances still add up to zero

  Scenario: Example 2 - Merchant 42 is paid out 98.50 EUR with a fee of 1.50
    Given a transaction "payment settled" has been recorded with entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 125.50 |
      | Merchant 42    | CREDIT    | 125.50 |
    When a transaction "payout" is recorded with entries:
      | account     | direction | amount |
      | Merchant 42 | DEBIT     | 100.00 |
      | Payouts EUR | CREDIT    | 98.50  |
      | Fees EUR    | CREDIT    | 1.50   |
    Then the transaction is recorded
    And the balance of "Merchant 42" is 25.50
    And the balance of "Payouts EUR" is 98.50
    And the balance of "Fees EUR" is 1.50
    And the EUR balances still add up to zero

  Scenario: Example 4 - a payment of 15,000 yen settles for Merchant 7
    When a transaction "payment settled" is recorded with entries:
      | account        | direction | amount |
      | Settlement JPY | DEBIT     | 15000  |
      | Merchant 7     | CREDIT    | 15000  |
    Then the transaction is recorded
    And the balance of "Merchant 7" is 15000
    And the balance of "Settlement JPY" changed by -15000

  Scenario Outline: Example 5 - refused, starting from the balances after example 2
    Given a transaction "payment settled" has been recorded with entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 125.50 |
      | Merchant 42    | CREDIT    | 125.50 |
    And a transaction "payout" has been recorded with entries:
      | account     | direction | amount |
      | Merchant 42 | DEBIT     | 100.00 |
      | Payouts EUR | CREDIT    | 98.50  |
      | Fees EUR    | CREDIT    | 1.50   |
    When a transaction "refused" is recorded with entries:
      | account         | direction | amount         |
      | <debit account> | DEBIT     | <debit amount> |
      | <credit account>| CREDIT    | <credit amount>|
    Then it is refused with <code> pointing at "<pointer>"
    And nothing was recorded
    And the balance of "Merchant 42" is 25.50

    Examples:
      | debit account | debit amount | credit account                 | credit amount | code               | pointer              |
      | Merchant 42   | 100.00       | Payouts EUR                    | 99.00         | UNBALANCED         | /entries             |
      | Merchant 42   | 50.00        | Payouts EUR                    | 50.00         | OVERDRAFT_EXCEEDED | /entries/0/accountId |
      | Merchant 7    | 100.5        | Payouts JPY                    | 100.5         | SCALE_EXCEEDED     | /entries/0/amount    |
      | Merchant 42   | 10.00        | Merchant 7                     | 10            | CURRENCY_MISMATCH  | /entries/1/accountId |
      | Merchant 42   | 10.00        | an account that does not exist | 10.00         | UNKNOWN_ACCOUNT    | /entries/1/accountId |

  Scenario: Example 5 - a transaction needs at least two entries
    When a transaction "one entry" is recorded with entries:
      | account     | direction | amount |
      | Merchant 42 | CREDIT    | 10.00  |
    Then it is refused with TOO_FEW_ENTRIES pointing at "/entries"
    And nothing was recorded

  Scenario: Every independent fault is reported at once
    When a transaction "many faults" is recorded with entries:
      | account                        | direction | amount |
      | an account that does not exist | DEBIT     | 10.00  |
      | Merchant 7                     | CREDIT    | 0.5    |
      | Merchant 42                    | CREDIT    | 0.00   |
    Then it is refused with these faults:
      | code                | pointer              |
      | UNKNOWN_ACCOUNT     | /entries/0/accountId |
      | SCALE_EXCEEDED      | /entries/1/amount    |
      | NON_POSITIVE_AMOUNT | /entries/2/amount    |
      | CURRENCY_MISMATCH   | /entries/2/accountId |
