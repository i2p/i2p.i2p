package net.i2p.sam.client;

import java.util.Properties;

/**
 * Basic noop client event listener
 */
public class SAMClientEventListenerImpl implements SAMReader.SAMClientEventListener {
    public void destReplyReceived(String publicKey, String privateKey) {}
    public void helloReplyReceived(boolean ok) {}
    public void namingReplyReceived(String name, String result, String value, String message) {}
    public void sessionStatusReceived(String result, String destination, String message) {}
    public void streamClosedReceived(String result, int id, String message) {}
    public void streamConnectedReceived(String remoteDestination, int id) {}
    public void streamDataReceived(int id, byte[] data, int offset, int length) {}
    public void streamStatusReceived(String result, int id, String message) {}
    public void unknownMessageReceived(String major, String minor, Properties params) {}
}
