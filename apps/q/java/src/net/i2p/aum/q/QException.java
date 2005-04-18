/*
 * QException.java
 *
 * Created on April 6, 2005, 2:05 PM
 */

package net.i2p.aum.q;

import java.io.PrintStream;
import java.io.PrintWriter;

/**
 * Base class of Q exceptions
 * @author jrandom (shamelessly rebadged by aum)
 */

public class QException extends Exception {
    private Throwable _source;

    public QException() {
        this(null, null);
    }

    public QException(String msg) {
        this(msg, null);
    }

    public QException(String msg, Throwable source) {
        super(msg);
        _source = source;
    }

    public void printStackTrace() {
        if (_source != null) _source.printStackTrace();
        super.printStackTrace();
    }

    public void printStackTrace(PrintStream ps) {
        if (_source != null) _source.printStackTrace(ps);
        super.printStackTrace(ps);
    }

    public void printStackTrace(PrintWriter pw) {
        if (_source != null) _source.printStackTrace(pw);
        super.printStackTrace(pw);
    }
}

