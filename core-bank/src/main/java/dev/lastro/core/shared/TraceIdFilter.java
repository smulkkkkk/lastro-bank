package dev.lastro.core.shared;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Identifica cada requisição nos logs, nas respostas de erro e, na etapa 3, nas chamadas ao Go. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String KEY = "traceId";
    static final String HEADER = "X-Trace-Id";
    // Valor vem do cliente e vai para o log: só aceita hexadecimal.
    private static final Pattern VALID = Pattern.compile("[0-9a-f]{32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String received = request.getHeader(HEADER);
        String traceId = received != null && VALID.matcher(received).matches()
                ? received
                : UUID.randomUUID().toString().replace("-", "");
        MDC.put(KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(KEY);
        }
    }
}
