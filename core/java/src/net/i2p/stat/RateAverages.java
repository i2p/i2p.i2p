package net.i2p.stat;

/**
 * Storage space for computations of various averages.
 * 
 * @author zab
 */
public class RateAverages {
    
    private double average, current, last;
    private long totalEventCount;
    
    public void reset() {
        average = 0;
        current = 0;
        last = 0;
        totalEventCount = 0;
    }

    public double getAverage() {
        return average;
    }

    public void setAverage(double average) {
        this.average = average;
    }

    public double getCurrent() {
        return current;
    }

    public void setCurrent(double current) {
        this.current = current;
    }

    public double getLast() {
        return last;
    }

    public void setLast(double last) {
        this.last = last;
    }
    
    public long getTotalEventCount() {
        return totalEventCount;
    }
    
    public void setTotalEventCount(long totalEventCount) {
        this.totalEventCount = totalEventCount;
    }
    
}
