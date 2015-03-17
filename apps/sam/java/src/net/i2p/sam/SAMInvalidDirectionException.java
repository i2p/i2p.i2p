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
 * Exception thrown by SAM methods when an application tries to create outgoing
 * connections through a receive-only SAM session.
 *
 * @author human
 */
public class SAMInvalidDirectionException extends Exception {
	static final long serialVersionUID = 1 ;
	
    public SAMInvalidDirectionException() {
	super();
    }
    
    public SAMInvalidDirectionException(String s) {
	super(s);
    }
}
