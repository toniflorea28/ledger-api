package com.toniflorea.ledger.application.port;

import java.time.Instant;

public interface LedgerClock {

	Instant now();
}
