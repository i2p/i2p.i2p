package net.i2p.sam.client;

import java.util.Properties;

/**
 * Basic noop client event listener
 */
public class SAMClientEventListenerImpl implements SAMReader.SAMClientEventListener {
    public void destReplyReceived(String publicKey, String privateKey) {}
    public void helloReplyReceived(boolean ok, String version) {}
    public void namingReplyReceived(String name, String result, String value, String message) {}
    public void sessionStatusReceived(String result, String destination, String message) {}
    public void streamClosedReceived(String result, String id, String message) {}
    public void streamConnectedReceived(String remoteDestination, String id) {}
    public void streamDataReceived(String id, byte[] data, int offset, int length) {}
    public void streamStatusReceived(String result, String id, String message) {}
    public void unknownMessageReceived(String major, String minor, Properties params) {}
}
