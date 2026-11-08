package com.toniflorea.ledger.application.port;

import java.time.Duration;

public interface RelayLease {

	boolean tryAcquire(String name, String owner, Duration ttl);
}
