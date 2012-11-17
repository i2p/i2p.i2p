package net.i2p.stat;

public class RateAverages {
    
    private double average, current, last;
    
    public void reset() {
        average = 0;
        current = 0;
        last = 0;
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
    
}
