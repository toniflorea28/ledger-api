package com.toniflorea.ledger.infrastructure;

import com.toniflorea.ledger.adapter.inbound.messaging.PoisonMessage;
import com.toniflorea.ledger.domain.error.ErrorCode;
import com.toniflorea.ledger.domain.error.LedgerException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
public class KafkaConfig {

	public static final String ERROR_CODE_HEADER = "ledger-error-code";
	public static final String ERROR_DETAIL_HEADER = "ledger-error-detail";

	private static final int PARTITIONS = 3;

	@Bean
	org.apache.kafka.clients.admin.NewTopic paymentSettledTopic(LedgerProperties properties) {
		return TopicBuilder.name(properties.topics().paymentSettled()).partitions(PARTITIONS).replicas(1).build();
	}

	@Bean
	org.apache.kafka.clients.admin.NewTopic paymentSettledDltTopic(LedgerProperties properties) {
		return TopicBuilder.name(properties.topics().paymentSettledDlt()).partitions(PARTITIONS).replicas(1).build();
	}

	@Bean
	org.apache.kafka.clients.admin.NewTopic transactionRecordedTopic(LedgerProperties properties) {
		return TopicBuilder.name(properties.topics().transactionRecorded()).partitions(PARTITIONS).replicas(1).build();
	}

	/**
	 * A message the ledger can never record (unparseable, unknown account, wrong
	 * currency, bad amount) goes straight to the dead-letter topic with the reason in
	 * its headers, and the partition moves on. Anything else is treated as transient
	 * and retried with backoff; the partition waits, because losing a payment is
	 * worse than delaying the ones behind it. After the retries are exhausted the
	 * record is dead-lettered too, so nothing is lost without trace.
	 */
	@Bean
	CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, LedgerProperties properties) {
		String dlt = properties.topics().paymentSettledDlt();
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer((KafkaOperations<?, ?>) template,
				(record, exception) -> new TopicPartition(dlt, -1));
		recoverer.setHeadersFunction(KafkaConfig::reasonHeaders);

		ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
		backOff.setMaxInterval(10_000);
		backOff.setMaxAttempts(10);
		DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
		handler.addNotRetryableExceptions(PoisonMessage.class, LedgerException.class);
		return handler;
	}

	private static Headers reasonHeaders(org.apache.kafka.clients.consumer.ConsumerRecord<?, ?> record,
			Exception exception) {
		Throwable cause = exception instanceof ListenerExecutionFailedException && exception.getCause() != null
				? exception.getCause() : exception;
		String code;
		String detail;
		if (cause instanceof PoisonMessage poison) {
			code = poison.code().name();
			detail = poison.getMessage();
		}
		else if (cause instanceof LedgerException refused) {
			code = refused.errors().get(0).code().name();
			detail = refused.getMessage();
		}
		else {
			code = ErrorCode.MALFORMED_REQUEST.name();
			detail = "retries exhausted: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
		}
		RecordHeaders headers = new RecordHeaders();
		headers.add(ERROR_CODE_HEADER, code.getBytes(StandardCharsets.UTF_8));
		headers.add(ERROR_DETAIL_HEADER, detail.getBytes(StandardCharsets.UTF_8));
		headers.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, record.topic().getBytes(StandardCharsets.UTF_8));
		return headers;
	}
}
