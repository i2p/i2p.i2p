/**
 * A basic implementation for the EchoTestAnalyzer.
 */
public class BasicEchoTestAnalyzer implements EchoTestAnalyzer {

    /**
     * How many events must appear until a detailed report is
     * printed. Default is every 20 events.
     */
    private static int REPORT_DELAY = 20;

    private static int SUMMARY_SIZE = 100;

    public BasicEchoTestAnalyzer() {
        this(20, 100);
    }

    public BasicEchoTestAnalyzer(int reportDelay, int summarySize) {
        REPORT_DELAY = reportDelay;
        SUMMARY_SIZE = summarySize;
    }

    private int events = 0, packetLosses = 0, packetLossesDisconnect = 0, disconnects = 0, disconnectsRefused = 0,
            delayCount = 0, lastDelayPtr = 0;
    private long minDelay = Long.MAX_VALUE, maxDelay = 0, delaySum = 0;
    private long[] lastDelays = new long[SUMMARY_SIZE];

    public synchronized void packetLossOccurred(boolean beforeDisconnect) {
        System.out.println("1: Packet lost" + (beforeDisconnect ? " before disconnect" : "") + ".");
        packetLosses++;
        if (beforeDisconnect) packetLossesDisconnect++;
        countEvent();
    }

    public synchronized void successOccurred(long delay) {
        System.out.println("0: Delay = " + delay);
        if (delay > maxDelay) maxDelay = delay;
        if (delay < minDelay) minDelay = delay;
        delaySum += delay;
        delayCount++;
        lastDelays[lastDelayPtr++] = delay;
        lastDelayPtr %= SUMMARY_SIZE;
        countEvent();
    }

    public synchronized void disconnected(boolean refused) {
        System.out.println("2: Disconnected" + (refused ? " (connection refused)" : "") + ".");
        disconnects++;
        if (refused) disconnectsRefused++;
        countEvent();
    }

    private void countEvent() {
        events++;
        if (events % REPORT_DELAY == 0) {
            int packets = packetLosses + delayCount;
            long delaySummary = 0;
            for (int i = 0; i < SUMMARY_SIZE; i++) {
                delaySummary += lastDelays[i];
            }
            System.out.println("++++++++++++++++ ECHO STATISTICS +++++++++++++++++++++++++"
                               + "\n++ Number of total echo messages: "
                               + packets
                               + "\n++ No response for "
                               + packetLosses
                               + "\n++    (of which "
                               + packetLossesDisconnect
                               + " due to a disconnect)"
                               + "\n++ Disconnects: "
                               + disconnects
                               + "\n++    (of which "
                               + disconnectsRefused
                               + " due to 'connection refused')"
                               + (disconnects > 0 || true ? "\n++ Average lost packets per disconnect: "
                                                            + (packetLossesDisconnect / (float) disconnects) : "")
                               + "\n++++++++++++++++++++++++++++++++++++++++++++++++++++++++"
                               + "\n++ Minimal delay: "
                               + minDelay
                               + "\n++ Average delay: "
                               + (delaySum / (float) delayCount)
                               + "\n++ Maximal delay: "
                               + maxDelay
                               + (delayCount >= SUMMARY_SIZE ? "\n++ Average delay over last " + SUMMARY_SIZE + ": "
                                                               + (delaySummary / (float) SUMMARY_SIZE) : "")
                               + "\n++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
        }
    }
}