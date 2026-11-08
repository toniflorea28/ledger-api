package com.toniflorea.ledger.adapter.inbound.messaging;

import com.toniflorea.ledger.application.usecase.RecordSettledPayment;
import com.toniflorea.ledger.domain.error.ErrorCode;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentSettledListener {

	private static final Logger log = LoggerFactory.getLogger(PaymentSettledListener.class);

	private final RecordSettledPayment recordSettledPayment;
	private final JsonMapper json;

	public PaymentSettledListener(RecordSettledPayment recordSettledPayment, JsonMapper json) {
		this.recordSettledPayment = recordSettledPayment;
		this.json = json;
	}

	@KafkaListener(topics = "${ledger.topics.payment-settled}", concurrency = "${ledger.consumer.concurrency:3}")
	public void onPaymentSettled(ConsumerRecord<String, String> record) {
		PaymentSettledMessage message = parse(record.value());
		try (MDC.MDCCloseable payment = MDC.putCloseable("paymentId", message.paymentId());
				MDC.MDCCloseable event = MDC.putCloseable("eventId", message.eventId())) {
			RecordSettledPayment.Outcome outcome = recordSettledPayment.record(message.toCommand());
			if (outcome.duplicate()) {
				log.info("payment {} already recorded as transaction {}; redelivery ignored", message.paymentId(),
						outcome.transactionId());
			}
			else {
				log.info("payment {} recorded as transaction {}", message.paymentId(), outcome.transactionId());
			}
		}
	}

	private PaymentSettledMessage parse(String value) {
		PaymentSettledMessage message;
		try {
			message = json.readValue(value, PaymentSettledMessage.class);
		}
		catch (JacksonException e) {
			throw new PoisonMessage(ErrorCode.MALFORMED_REQUEST, "message is not a payment-settled JSON document: "
					+ e.getOriginalMessage());
		}
		if (message == null) {
			throw new PoisonMessage(ErrorCode.MALFORMED_REQUEST, "message is empty");
		}
		List<String> missing = message.missingFields();
		if (!missing.isEmpty()) {
			throw new PoisonMessage(ErrorCode.MALFORMED_REQUEST, "message is missing " + String.join(", ", missing));
		}
		return message;
	}
}
