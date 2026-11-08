package com.toniflorea.ledger.adapter.inbound.rest;

import com.toniflorea.ledger.application.idempotency.IdempotentExecutor;
import com.toniflorea.ledger.application.idempotency.StoredResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class IdempotentRequests {

	private final IdempotentExecutor executor;
	private final JsonMapper json;

	public IdempotentRequests(IdempotentExecutor executor, JsonMapper json) {
		this.executor = executor;
		this.json = json;
	}

	public <T> ResponseEntity<T> execute(String operation, String key, String path, Object requestBody,
			Class<T> responseType, Supplier<ResponseEntity<T>> action) {
		byte[] fingerprint = fingerprint("POST", path, requestBody);
		StoredResponse stored = executor.execute(operation, key, fingerprint,
				() -> toStored(action.get()),
				refusal -> new StoredResponse(Problems.statusOf(refusal).value(),
						json.writeValueAsString(Problems.render(refusal, path)), null));
		if (!stored.isSuccess()) {
			throw new StoredProblem(stored.status(), stored.body());
		}
		ResponseEntity.BodyBuilder response = ResponseEntity.status(stored.status());
		if (stored.location() != null) {
			response.location(URI.create(stored.location()));
		}
		return response.body(json.readValue(stored.body(), responseType));
	}

	private StoredResponse toStored(ResponseEntity<?> response) {
		URI location = response.getHeaders().getLocation();
		return new StoredResponse(response.getStatusCode().value(), json.writeValueAsString(response.getBody()),
				location == null ? null : location.toString());
	}

	/**
	 * SHA-256 over method, path and the canonical JSON of the parsed body, so two
	 * requests that differ only in whitespace or field order count as the same
	 * request, while any change in content does not.
	 */
	private byte[] fingerprint(String method, String path, Object body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(method.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
			digest.update(path.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) '\n');
			digest.update((body == null ? "" : json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8));
			return digest.digest();
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
