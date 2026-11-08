package com.toniflorea.ledger.adapter.outbound.persistence;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class Uuids {

	private Uuids() {
	}

	public static byte[] toBytes(UUID uuid) {
		return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits())
				.array();
	}

	public static UUID fromBytes(byte[] bytes) {
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		return new UUID(buffer.getLong(), buffer.getLong());
	}
}
