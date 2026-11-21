package com.example.wallet.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Sliding-window rate limiter backed by Redis.
 *
 * <p>Algorithm (atomic Lua script):
 * <ol>
 *   <li>Remove all entries older than {@code now - windowMs}.
 *   <li>Count remaining entries in the sorted set.
 *   <li>If count &lt; limit: add the current request (score = now) and allow.
 *   <li>Otherwise: reject with 429.
 * </ol>
 *
 * <p>Key = {@code rate:<userId>:<path-prefix>}.  Unauthenticated requests fall
 * back to the client IP so the auth endpoints are also protected.
 *
 * <p>Default limits (overridable via application.yml):
 * <ul>
 *   <li>Write endpoints ({@code /topup}, {@code /transfer}): 20 req / 10 s per user.
 *   <li>All other endpoints: 100 req / 60 s per user.
 * </ul>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // language=Lua
    private static final String SLIDING_WINDOW_LUA = """
            local key      = KEYS[1]
            local now      = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit    = tonumber(ARGV[3])
            local ttlMs    = tonumber(ARGV[4])
            redis.call('ZREMRANGEBYSCORE', key, '-inf', now - windowMs)
            local count = redis.call('ZCARD', key)
            if count < limit then
                redis.call('ZADD', key, now, now .. '-' .. math.random(1, 1000000))
                redis.call('PEXPIRE', key, ttlMs)
                return 1
            end
            return 0
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> script;
    private final RateLimitProperties props;

    public RateLimitFilter(StringRedisTemplate redis, RateLimitProperties props) {
        this.redis  = redis;
        this.props  = props;
        this.script = RedisScript.of(SLIDING_WINDOW_LUA, Long.class);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip rate-limiting for non-write auth endpoints to avoid blocking registration/login.
        // The write endpoints (/topup, /transfer) get a tighter bucket.
        boolean isWriteEndpoint = path.contains("/topup") || path.contains("/transfer");

        String identity = resolveIdentity(request);
        String bucket   = isWriteEndpoint ? "write" : "read";
        String key      = "rate:" + identity + ":" + bucket;

        long now      = System.currentTimeMillis();
        long windowMs = isWriteEndpoint ? props.getWriteWindowMs()  : props.getReadWindowMs();
        long limit    = isWriteEndpoint ? props.getWriteLimit()      : props.getReadLimit();
        long ttlMs    = windowMs * 2; // keep the key alive at least one full window after last hit

        Long allowed;
        try {
            allowed = redis.execute(script, List.of(key),
                    String.valueOf(now),
                    String.valueOf(windowMs),
                    String.valueOf(limit),
                    String.valueOf(ttlMs));
        } catch (Exception ex) {
            // Redis unavailable → fail open (don't block traffic)
            log.warn("Redis rate-limit check failed, failing open: {}", ex.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (allowed != null && allowed == 1L) {
            response.addHeader("X-RateLimit-Limit",  String.valueOf(limit));
            chain.doFilter(request, response);
        } else {
            log.debug("Rate limit exceeded for identity={} bucket={}", identity, bucket);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests – slow down.\"}");
        }
    }

    /** Use JWT subject when present, otherwise fall back to remote IP. */
    private String resolveIdentity(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            // Extract subject from JWT without full parsing (safe: JwtAuthFilter already validated it)
            try {
                String[] parts = auth.substring(7).split("\\.");
                if (parts.length == 3) {
                    byte[] decoded = java.util.Base64.getUrlDecoder()
                            .decode(padBase64(parts[1]));
                    String json = new String(decoded, StandardCharsets.UTF_8);
                    // extract "sub":"<uuid>" without a JSON library dependency here
                    int subIdx = json.indexOf("\"sub\"");
                    if (subIdx >= 0) {
                        int colon = json.indexOf(':', subIdx);
                        int q1    = json.indexOf('"', colon + 1);
                        int q2    = json.indexOf('"', q1 + 1);
                        if (q1 >= 0 && q2 > q1) {
                            return json.substring(q1 + 1, q2);
                        }
                    }
                }
            } catch (Exception ignored) { /* fall through to IP */ }
        }
        String xff = request.getHeader("X-Forwarded-For");
        return (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim()
                                               : request.getRemoteAddr();
    }

    private static String padBase64(String s) {
        return switch (s.length() % 4) {
            case 2  -> s + "==";
            case 3  -> s + "=";
            default -> s;
        };
    }
}
