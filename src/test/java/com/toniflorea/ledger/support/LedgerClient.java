package com.toniflorea.ledger.support;

import com.toniflorea.ledger.adapter.inbound.rest.api.model.AccountDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.DirectionDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.EntryRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.OpenAccountRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.RecordTransactionRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.ReverseTransactionRequestDto;
import com.toniflorea.ledger.adapter.inbound.rest.api.model.TransactionDto;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

public final class LedgerClient {

	public record Response<T>(int status, T body, Map<String, Object> problem, String location, String contentType) {

		public boolean isSuccess() {
			return status >= 200 && status < 300;
		}

		@SuppressWarnings("unchecked")
		public List<Map<String, Object>> errors() {
			return (List<Map<String, Object>>) problem.getOrDefault("errors", List.of());
		}

		public String code(int index) {
			return (String) errors().get(index).get("code");
		}

		public String pointer(int index) {
			return (String) errors().get(index).get("pointer");
		}

		public String detail(int index) {
			return (String) errors().get(index).get("detail");
		}
	}

	private final RestClient rest;
	private final JsonMapper json;

	public LedgerClient(String baseUrl, JsonMapper json) {
		this.rest = RestClient.builder().baseUrl(baseUrl).build();
		this.json = json;
	}

	public static String key() {
		return UUID.randomUUID().toString();
	}

	public static EntryRequestDto debit(UUID accountId, String amount) {
		return new EntryRequestDto().accountId(accountId).direction(DirectionDto.DEBIT).amount(amount);
	}

	public static EntryRequestDto credit(UUID accountId, String amount) {
		return new EntryRequestDto().accountId(accountId).direction(DirectionDto.CREDIT).amount(amount);
	}

	public Response<AccountDto> openAccount(String currency, String overdraftLimit, String idempotencyKey) {
		return exchange(HttpMethod.POST, "/v1/accounts", Map.of("Idempotency-Key", idempotencyKey),
				new OpenAccountRequestDto().currency(currency).overdraftLimit(overdraftLimit), AccountDto.class);
	}

	public Response<AccountDto> getAccount(UUID id) {
		return exchange(HttpMethod.GET, "/v1/accounts/" + id, Map.of(), null, AccountDto.class);
	}

	public Response<AccountDto[]> settlementAccounts() {
		return exchange(HttpMethod.GET, "/v1/settlement-accounts", Map.of(), null, AccountDto[].class);
	}

	public Response<TransactionDto> record(String description, String idempotencyKey, EntryRequestDto... entries) {
		return exchange(HttpMethod.POST, "/v1/transactions", Map.of("Idempotency-Key", idempotencyKey),
				new RecordTransactionRequestDto().description(description).entries(Arrays.asList(entries)),
				TransactionDto.class);
	}

	public Response<TransactionDto> getTransaction(UUID id) {
		return exchange(HttpMethod.GET, "/v1/transactions/" + id, Map.of(), null, TransactionDto.class);
	}

	public Response<TransactionDto> reverse(UUID transactionId, String idempotencyKey, String description) {
		return exchange(HttpMethod.POST, "/v1/transactions/" + transactionId + "/reversals",
				Map.of("Idempotency-Key", idempotencyKey),
				description == null ? null : new ReverseTransactionRequestDto().description(description),
				TransactionDto.class);
	}

	public Response<String> raw(HttpMethod method, String path, Map<String, String> headers, String body) {
		return exchange(method, path, headers, body, String.class);
	}

	private <T> Response<T> exchange(HttpMethod method, String path, Map<String, String> headers, Object body,
			Class<T> type) {
		RestClient.RequestBodySpec spec = rest.method(method).uri(path).headers(h -> headers.forEach(h::set));
		if (body != null) {
			spec.contentType(MediaType.APPLICATION_JSON).body(body);
		}
		return spec.exchange((request, response) -> {
			int status = response.getStatusCode().value();
			byte[] bytes = response.getBody().readAllBytes();
			String location = response.getHeaders().getFirst("Location");
			MediaType mediaType = response.getHeaders().getContentType();
			String contentType = mediaType == null ? null : mediaType.toString();
			if (status < 300) {
				T parsed = bytes.length == 0 ? null
						: type == String.class ? type.cast(new String(bytes)) : json.readValue(bytes, type);
				return new Response<>(status, parsed, Map.of(), location, contentType);
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> problem = bytes.length == 0 ? Map.of() : json.readValue(bytes, Map.class);
			return new Response<>(status, null, problem, location, contentType);
		});
	}
}
