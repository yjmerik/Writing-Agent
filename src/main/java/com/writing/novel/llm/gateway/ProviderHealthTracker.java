package com.writing.novel.llm.gateway;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Process-local circuit state. Epochs prevent an older in-flight success from closing a newer failure. */
final class ProviderHealthTracker {
    record Lease(String provider, long epoch, boolean probe) {}
    private static final class State {
        long epoch;
        Instant retryAt;
        boolean probing;
    }
    private final Map<String, State> states = new HashMap<>();
    private final Clock clock;
    private final long cooldownSeconds;
    ProviderHealthTracker(Clock clock, long cooldownSeconds) {
        this.clock = clock; this.cooldownSeconds = cooldownSeconds;
    }
    synchronized Lease acquire(String id) {
        State state = states.computeIfAbsent(id, key -> new State());
        if (state.retryAt == null) return new Lease(id, state.epoch, false);
        if (state.probing || clock.instant().isBefore(state.retryAt)) return null;
        state.probing = true;
        return new Lease(id, state.epoch, true);
    }
    synchronized void fail(Lease lease) {
        State s = states.get(lease.provider());
        if (s.epoch != lease.epoch()) return;
        s.retryAt = clock.instant().plusSeconds(cooldownSeconds);
        s.probing = false;
        s.epoch++;
    }
    synchronized boolean succeed(Lease lease) {
        State s = states.get(lease.provider());
        if (s.epoch != lease.epoch() || !lease.probe()) return false;
        s.retryAt = null;
        s.probing = false;
        s.epoch++;
        return true;
    }
    synchronized void release(Lease lease) {
        State s = states.get(lease.provider());
        if (lease.probe() && s.epoch == lease.epoch()) s.probing = false;
    }
    synchronized String nextAttempt(String id) {
        State s = states.get(id);
        return s == null || s.retryAt == null ? "now" : s.retryAt.toString() + (s.probing ? " (probe in flight)" : "");
    }
}
