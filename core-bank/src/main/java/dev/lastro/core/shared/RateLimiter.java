package dev.lastro.core.shared;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Janela fixa de um minuto, em memória. Vale por instância: com mais de uma no ar, o limite real é
 * multiplicado pela quantidade delas.
 */
@Component
public class RateLimiter {

    private static final int MAX_TRACKED = 10_000;

    private record Window(long minute, int count) {
    }

    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void check(String key, int perMinute) {
        long minute = clock.millis() / 60_000;
        if (windows.size() > MAX_TRACKED) {
            windows.values().removeIf(window -> window.minute() != minute);
        }
        Window window = windows.compute(key, (k, old) ->
                old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        if (window.count() > perMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "request.rate_limited",
                    "Muitas tentativas. Espere um minuto e tente de novo.");
        }
    }
}
