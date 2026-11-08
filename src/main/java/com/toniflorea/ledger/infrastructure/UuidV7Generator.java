package com.toniflorea.ledger.infrastructure;

import com.toniflorea.ledger.application.port.IdGenerator;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * UUIDv7: millisecond timestamp in the high bits, random low bits. Time-ordered
 * ids keep InnoDB primary-key inserts appending instead of splitting pages.
 */
@Component
public class UuidV7Generator implements IdGenerator {

	private final SecureRandom random = new SecureRandom();

	@Override
	public UUID next() {
		long millis = System.currentTimeMillis();
		long randA = random.nextLong() & 0x0FFFL;
		long randB = random.nextLong() & 0x3FFFFFFFFFFFFFFFL;
		long msb = (millis << 16) | 0x7000L | randA;
		long lsb = 0x8000000000000000L | randB;
		return new UUID(msb, lsb);
	}
}
