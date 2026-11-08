Feature: Reversing transactions
  A recorded transaction is never changed. A mistake is corrected by a reversal: a new
  transaction with every entry flipped, linked to the original. A transaction can be
  reversed only once, and a reversal itself cannot be reversed.

  Background:
    Given an account "Merchant 42" in EUR
    And an account "Payouts EUR" in EUR
    And an account "Fees EUR" in EUR
    And a transaction "payment settled" has been recorded with entries:
      | account        | direction | amount |
      | Settlement EUR | DEBIT     | 125.50 |
      | Merchant 42    | CREDIT    | 125.50 |
    And a transaction "payout" has been recorded with entries:
      | account     | direction | amount |
      | Merchant 42 | DEBIT     | 100.00 |
      | Payouts EUR | CREDIT    | 98.50  |
      | Fees EUR    | CREDIT    | 1.50   |

  Scenario: Example 3 - the payout is reversed
    When the transaction "payout" is reversed
    Then a new transaction is recorded that reverses "payout"
    And its entries are:
      | account     | direction | amount |
      | Merchant 42 | CREDIT    | 100.00 |
      | Payouts EUR | DEBIT     | 98.50  |
      | Fees EUR    | DEBIT     | 1.50   |
    And the balance of "Merchant 42" is 125.50
    And the balance of "Payouts EUR" is 0.00
    And the balance of "Fees EUR" is 0.00
    And the transaction "payout" stays exactly as it was
    And the EUR balances still add up to zero

  Scenario: Reversing a second time is refused
    Given the transaction "payout" has been reversed
    When the transaction "payout" is reversed
    Then it is refused with ALREADY_REVERSED

  Scenario: A reversal cannot be reversed
    Given the transaction "payout" has been reversed
    When the reversal of "payout" is reversed
    Then it is refused with CANNOT_REVERSE_REVERSAL

  Scenario: A reversal obeys overdraft limits like any transaction
    Given a transaction "spend" has been recorded with entries:
      | account     | direction | amount |
      | Payouts EUR | DEBIT     | 98.50  |
      | Merchant 42 | CREDIT    | 98.50  |
    When the transaction "payout" is reversed
    Then it is refused with OVERDRAFT_EXCEEDED pointing at "/entries/1/accountId"
    And nothing was recorded
