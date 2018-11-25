package net.i2p.i2pcontrol.security;

public class ExpiredAuthTokenException extends Exception {
    private static final long serialVersionUID = 2279019346592900289L;

    private String expiryTime;

    public ExpiredAuthTokenException(String str, String expiryTime) {
        super(str);
        this.expiryTime = expiryTime;
    }

    public String getExpirytime() {
        return expiryTime;
    }
}
