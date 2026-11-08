package com.toniflorea.ledger.application.port;

import com.toniflorea.ledger.application.event.TransactionRecordedEvent;

public interface OutboxRepository {

	void append(TransactionRecordedEvent event);
}
