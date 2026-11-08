Feature: Recording settled payments
  The payment system sends a payment-settled message through the broker. Each message
  records a transaction that debits the settlement account in that currency and credits
  the merchant's account. The same payment is recorded once however many times it
  arrives, and a message that cannot be recorded does not block the ones behind it.

  Background:
    Given an account "Merchant 42" in EUR

  Scenario: A settled payment is recorded from a message
    When a payment-settled message arrives for "Merchant 42" of 125.50 EUR
    Then within 30 seconds the balance of "Merchant 42" is 125.50
    And the payment is marked as processed

  Scenario: Redelivery under the same or a new event id is recorded once
    Given a payment-settled message has arrived for "Merchant 42" of 40.00 EUR
    When the same payment is redelivered with the same event id
    And the same payment is redelivered with a new event id
    And a marker payment of 1.00 EUR is processed
    Then the balance of "Merchant 42" is 40.00
    And "Merchant 42" has 1 entry

  Scenario: A message that cannot be recorded is dead-lettered with its reason and does not block the next
    Given an account "Merchant 7" in JPY
    When a malformed payment-settled message arrives
    And a payment-settled message arrives for an unknown account of 10.00 EUR
    And a payment-settled message arrives for "Merchant 7" of 10.00 EUR
    And a payment-settled message arrives for "Merchant 42" of 2.00 EUR
    Then within 30 seconds the balance of "Merchant 42" is 2.00
    And the balance of "Merchant 7" is 0
    And the dead-letter topic holds those three messages with reasons:
      | message                    | reason            |
      | the malformed message      | MALFORMED_REQUEST |
      | the unknown-account message| UNKNOWN_ACCOUNT   |
      | the wrong-currency message | CURRENCY_MISMATCH |
