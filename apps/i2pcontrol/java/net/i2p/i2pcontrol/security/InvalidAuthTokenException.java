package net.i2p.i2pcontrol.security;

public class InvalidAuthTokenException extends Exception {
    private static final long serialVersionUID = 7605321329341235577L;

    public InvalidAuthTokenException(String str) {
        super(str);
    }
}
