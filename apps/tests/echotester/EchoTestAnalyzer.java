
/**
 * A class that wants to analyze tests implements this interface. This
 * allows to "mix" several test values (from different echo servers)
 * as well as different algorithms for analyzing the data (for
 * jrandom: Strategy Pattern *g*).
 */
public interface EchoTestAnalyzer {

    public void packetLossOccurred(boolean beforeDisconnect);

    public void successOccurred(long delay);

    public void disconnected(boolean refused);

}

