package i2p.bote.web;

/**
 * Represents the return status of a JSP function.
 *
 * @author HungryHobo@mail.i2p
 */
public class ReturnStatus {

    private boolean error;
    private String message;
    
    public ReturnStatus(boolean error, String message) {
        this.setError(error);
        this.setMessage(message);
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public boolean isError() {
        return error;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}