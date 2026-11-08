package com.toniflorea.ledger.application.port;

import java.util.UUID;

public interface IdGenerator {

	UUID next();
}
