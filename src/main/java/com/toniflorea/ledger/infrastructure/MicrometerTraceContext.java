package com.toniflorea.ledger.infrastructure;

import com.toniflorea.ledger.application.port.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class MicrometerTraceContext implements TraceContext {

	private final Tracer tracer;
	private final Propagator propagator;

	public MicrometerTraceContext(Tracer tracer, Propagator propagator) {
		this.tracer = tracer;
		this.propagator = propagator;
	}

	@Override
	public Map<String, String> currentHeaders() {
		Span span = tracer.currentSpan();
		if (span == null) {
			return Map.of();
		}
		Map<String, String> headers = new LinkedHashMap<>();
		propagator.inject(span.context(), headers, Map::put);
		return headers;
	}
}
