package net.i2p.stat;

/**
 * Component to be notified when a particular event occurs
 */
public interface StatLog {
    public void addData(String scope, String stat, long value, long duration);
}
