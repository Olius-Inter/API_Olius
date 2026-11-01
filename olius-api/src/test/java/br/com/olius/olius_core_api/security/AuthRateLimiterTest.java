package br.com.olius.olius_core_api.security;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AuthRateLimiterTest {
    @Test void rejectsExcessAndSeparatesIps() {
        var limiter=new AuthRateLimiter(2,10,Clock.systemUTC());
        assertThat(limiter.allow("first")).isTrue(); assertThat(limiter.allow("first")).isTrue();
        assertThat(limiter.allow("first")).isFalse(); assertThat(limiter.allow("second")).isTrue();
    }
    @Test void capacityCannotBeBypassedByEvictingActiveEntries() {
        var limiter=new AuthRateLimiter(2,1,Clock.systemUTC());
        assertThat(limiter.allow("first")).isTrue(); assertThat(limiter.allow("second")).isFalse();
    }
    @Test void capacityPressureCannotResetAnExhaustedQuota() {
        var clock=new MutableClock();
        var limiter=new AuthRateLimiter(1,1,clock,Duration.ofMinutes(15));
        assertThat(limiter.allow("tracked")).isTrue();
        assertThat(limiter.allow("tracked")).isFalse();
        for(int i=0;i<20;i++) assertThat(limiter.allow("rotating-"+i)).isFalse();
        assertThat(limiter.allow("tracked")).isFalse();
        clock.now=clock.now.plus(Duration.ofMinutes(15));
        assertThat(limiter.allow("new-after-expiry")).isTrue();
    }
    @Test void fullCapacityStillAllowsRemainingQuotaOfAnExistingKey() {
        var limiter=new AuthRateLimiter(2,1,Clock.systemUTC());
        assertThat(limiter.allow("tracked")).isTrue();
        assertThat(limiter.allow("untracked")).isFalse();
        assertThat(limiter.allow("tracked")).isTrue();
        assertThat(limiter.allow("tracked")).isFalse();
    }
    @Test void windowExpires() {
        var clock=new MutableClock(); var limiter=new AuthRateLimiter(1,1,clock);
        assertThat(limiter.allow("first")).isTrue(); assertThat(limiter.allow("first")).isFalse();
        clock.now=clock.now.plusSeconds(60); assertThat(limiter.allow("second")).isTrue();
    }
    private static class MutableClock extends Clock {
        private Instant now=Instant.EPOCH;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
