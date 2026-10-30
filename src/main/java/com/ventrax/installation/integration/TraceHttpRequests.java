package com.ventrax.installation.integration;

public final class TraceHttpRequests
        implements org.springframework.http.client.ClientHttpRequestInterceptor {
    public org.springframework.http.client.ClientHttpResponse intercept(
            org.springframework.http.HttpRequest request,
            byte[] body,
            org.springframework.http.client.ClientHttpRequestExecution execution)
            throws java.io.IOException {
        String correlation = org.slf4j.MDC.get("correlationId");
        if (correlation != null) request.getHeaders().set("X-Correlation-ID", correlation);
        return execution.execute(request, body);
    }
}
