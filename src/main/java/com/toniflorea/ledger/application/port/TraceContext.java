package com.toniflorea.ledger.application.port;

import java.util.Map;

public interface TraceContext {

	Map<String, String> currentHeaders();
}
