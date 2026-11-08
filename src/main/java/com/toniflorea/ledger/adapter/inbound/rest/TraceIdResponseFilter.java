package com.toniflorea.ledger.adapter.inbound.rest;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TraceIdResponseFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Trace-Id";

	private final Tracer tracer;

	public TraceIdResponseFilter(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Span span = tracer.currentSpan();
		if (span != null) {
			response.setHeader(HEADER, span.context().traceId());
		}
		chain.doFilter(request, response);
	}
}
