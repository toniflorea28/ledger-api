package com.toniflorea.ledger.adapter.inbound.rest;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class CorrelationHeadersFilter extends OncePerRequestFilter {

	public static final String TRACE_ID = "X-Trace-Id";
	public static final String INSTANCE_ID = "X-Instance-Id";

	private final Tracer tracer;
	private final String instanceId;

	public CorrelationHeadersFilter(Tracer tracer, @Value("${ledger.instance-id:local}") String instanceId) {
		this.tracer = tracer;
		this.instanceId = instanceId;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Span span = tracer.currentSpan();
		if (span != null) {
			response.setHeader(TRACE_ID, span.context().traceId());
		}
		response.setHeader(INSTANCE_ID, instanceId);
		chain.doFilter(request, response);
	}
}
