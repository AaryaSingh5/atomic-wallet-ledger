package com.example.wallet.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalisable rate-limit thresholds.
 *
 * <pre>
 * app:
 *   rate-limit:
 *     write-limit:     20     # requests allowed per write window
 *     write-window-ms: 10000  # 10 s sliding window for /topup and /transfer
 *     read-limit:      100    # requests allowed per read window
 *     read-window-ms:  60000  # 60 s sliding window for everything else
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    private long writeLimit    = 20;
    private long writeWindowMs = 10_000;
    private long readLimit     = 100;
    private long readWindowMs  = 60_000;

    public long getWriteLimit()    { return writeLimit; }
    public long getWriteWindowMs() { return writeWindowMs; }
    public long getReadLimit()     { return readLimit; }
    public long getReadWindowMs()  { return readWindowMs; }

    public void setWriteLimit(long v)    { this.writeLimit    = v; }
    public void setWriteWindowMs(long v) { this.writeWindowMs = v; }
    public void setReadLimit(long v)     { this.readLimit     = v; }
    public void setReadWindowMs(long v)  { this.readWindowMs  = v; }
}
