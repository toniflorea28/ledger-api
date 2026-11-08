Feature: Opening accounts
  Money is held in accounts. An account has a currency (EUR, USD or JPY), an overdraft
  limit that is zero unless set when the account is opened, and a balance that starts at zero.

  Scenario: An account opens at zero with the limit it was given
    When an account is opened in EUR with an overdraft limit of 30.00
    Then the account is open
    And its balance is 0.00
    And its available balance is 30.00

  Scenario: The overdraft limit is zero unless set
    When an account is opened in JPY
    Then the account is open
    And its overdraft limit is 0

  Scenario Outline: Currencies the ledger does not accept
    When an account is opened in <currency>
    Then it is refused with <code> pointing at "/currency"

    Examples:
      | currency | code                  |
      | EURO     | INVALID_CURRENCY_CODE |
      | ABC      | INVALID_CURRENCY_CODE |
      | GBP      | UNSUPPORTED_CURRENCY  |

  Scenario: The ledger's own settlement accounts exist from the start
    Then there is a settlement account for each of EUR, USD and JPY
    And settlement accounts have no overdraft limit
