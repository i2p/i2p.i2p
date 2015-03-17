/*
 * Created on Jul 16, 2004
 */
package freenet.support.CPUInformation;

/**
 * @author Iakin
 *
 */
public class UnknownCPUException extends RuntimeException {
    /**
	 * 
	 */
	private static final long serialVersionUID = 5166144274582583742L;

	public UnknownCPUException() {
        super();
    }

    public UnknownCPUException(String message) {
        super(message);
    }
}
