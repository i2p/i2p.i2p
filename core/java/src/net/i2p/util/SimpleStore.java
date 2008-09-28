/*
 * This is free software, do as you please.
 */

package net.i2p.util;

/**
 *
 * @author sponge
 */
public class SimpleStore {

    private boolean answer;

    SimpleStore(boolean x) {
        answer=x;
    }

    /**
     * set the answer
     * 
     * @param x 
     */
    public void setAnswer(boolean x) {
        answer = x;
    }
    /**
     * 
     * @return boolean
     */
    public boolean getAnswer() {
        return answer;
    }

}
