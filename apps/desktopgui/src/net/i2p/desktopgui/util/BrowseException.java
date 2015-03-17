package net.i2p.desktopgui.util;

public class BrowseException extends Exception {

	private static final long serialVersionUID = 1L;
	
	public BrowseException() {
        super();
    }
    
    public BrowseException(String s) {
        super(s);
    }
    
    public BrowseException(String s, Throwable t) {
        super(s, t);
    }
    
    public BrowseException(Throwable t) {
        super(t);
    }
    
}