package net.i2p.router.transport.udp;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic tests of recovery probing; no router or network is started. */
public final class ProbeRetryTest {
    public static void main(String[] args) throws Exception {
        AtomicLong sent = new AtomicLong();
        TransportRecoveryLab.probeRoundTrip(sent::incrementAndGet, id -> id == 1, 5000, 10);
        require(sent.get() == 1, "an immediate reply must not require another probe");

        sent.set(0);
        TransportRecoveryLab.probeRoundTrip(sent::incrementAndGet, id -> id == 2, 5000, 10);
        require(sent.get() == 2, "a lost first request must allow a fresh successful request");

        sent.set(0);
        TransportRecoveryLab.probeRoundTrip(sent::incrementAndGet, id -> sent.get() >= 3 && id == 1, 5000, 10);
        require(sent.get() == 3, "a late reply to an earlier request must still count");

        sent.set(0);
        try {
            TransportRecoveryLab.probeRoundTrip(sent::incrementAndGet, id -> id == 999, 100, 10);
            throw new AssertionError("unrelated replies must not satisfy the deadline");
        } catch (TimeoutException expected) {
            require(sent.get() >= 1 && sent.get() <= 10, "the deadline must bound fresh requests");
        }
        sent.set(0);
        try {
            TransportRecoveryLab.probeRoundTrip(sent::incrementAndGet, id -> {
                try { Thread.sleep(150); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return true;
            }, 100, 10);
            throw new AssertionError("a reply observed after the deadline must fail");
        } catch (TimeoutException expected) {
            require(sent.get() == 1, "a slow reply check must not send past the deadline");
        }
        System.out.println("Five probe retry tests passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
