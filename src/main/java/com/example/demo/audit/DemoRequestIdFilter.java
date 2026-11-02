package com.example.demo.audit;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class DemoRequestIdFilter extends OncePerRequestFilter {
public static final String ATTRIBUTE = "demoRequestId";

protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain chain
) throws ServletException, IOException {

    String requestId = UUID.randomUUID().toString();

    request.setAttribute(ATTRIBUTE, requestId);
    response.setHeader("X-Request-Id", requestId);

    chain.doFilter(request, response);
}
}