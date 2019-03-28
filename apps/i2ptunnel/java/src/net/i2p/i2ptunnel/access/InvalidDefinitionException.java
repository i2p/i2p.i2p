package net.i2p.i2ptunnel.access;

public class InvalidDefinitionException extends Exception {
    public InvalidDefinitionException(String reason) {
        super(reason);
    }

    public InvalidDefinitionException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
