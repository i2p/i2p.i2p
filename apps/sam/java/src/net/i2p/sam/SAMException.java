package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't  make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Exception thrown by SAM methods
 *
 * @author human
 */
public class SAMException extends Exception {

	static final long serialVersionUID = 1 ;

    public SAMException() {
    	super();
    }
    
    public SAMException(String s) {
    	super(s);
    }
}
