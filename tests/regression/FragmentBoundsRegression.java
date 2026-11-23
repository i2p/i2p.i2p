package net.i2p.router.tunnel;

import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import net.i2p.I2PAppContext;

/** Calls the real reassembly helper with adversarial slices and ordinary fragments. */
public final class FragmentBoundsRegression {
    private static int checks, failures;

    public static void main(String[] args) {
        I2PAppContext context = new I2PAppContext(new Properties());
        context.logManager().setDefaultLimit("CRIT");
        try {
            byte[] payload = new byte[1024];
            Arrays.fill(payload, (byte) 71);
            int[] offsets = { -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE - 20, 1024, 1025 };
            for (boolean follow : new boolean[] { false, true }) {
                for (int offset : offsets) invalid(context, payload, offset, 32, follow);
                for (int size : new int[] { -1, 0, 997, Integer.MAX_VALUE }) invalid(context, payload, 0, size, follow);
                Random random = new Random(1024);
                for (int i = 0; i < 64; i++) {
                    int offset = random.nextBoolean() ? -1 - random.nextInt(Integer.MAX_VALUE)
                                                     : Integer.MAX_VALUE - random.nextInt(64);
                    invalid(context, payload, offset, 64, follow);
                }
                FragmentedMessage message = new FragmentedMessage(context, follow ? 2 : 1);
                check(!message.receive(0, payload, 0, 1, true), "follow-on zero rejected");
                check(!message.receive(64, payload, 0, 1, true), "follow-on 64 rejected");
                check(message.receive(1, payload, 1023, 1, true), "exact-ending follow-on accepted");
                check(message.receive(payload, 28, 996, false, null, null), "maximum initial fragment accepted");
                check(message.isComplete(), "valid message completes");
                byte[] complete = message.toByteArray();
                check(complete.length == 997, "valid completed size preserved");
                check(Arrays.equals(complete, Arrays.copyOf(payload, 997)), "valid bytes preserved");
            }
            System.out.println("FRAGMENT_BOUNDS_CHECKS=" + checks + "; FAILURES=" + failures);
            if (failures != 0) throw new AssertionError("fragment slice validation failed");
        } finally { context.logManager().shutdown(); }
    }

    private static void invalid(I2PAppContext context, byte[] payload, int offset, int size, boolean follow) {
        FragmentedMessage message = new FragmentedMessage(context, 3);
        boolean rejected;
        try {
            rejected = !(follow ? message.receive(1, payload, offset, size, true)
                                : message.receive(payload, offset, size, false, null, null));
        } catch (RuntimeException unexpected) { rejected = false; }
        check(rejected, "invalid slice returns false: follow=" + follow + ", offset=" + offset + ", size=" + size);
        check(message.getFragmentCount() == 0 && !message.isComplete(), "invalid slice leaves state empty");
        message.failed();
    }
    private static void check(boolean ok, String description) {
        checks++;
        if (!ok) { failures++; System.out.println("FAIL: " + description); }
    }
}
