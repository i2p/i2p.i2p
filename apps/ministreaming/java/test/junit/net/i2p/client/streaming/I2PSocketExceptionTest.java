package net.i2p.client.streaming;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.util.HashMap;
import java.util.Map;

import net.i2p.client.SendMessageStatusListener;
import net.i2p.data.i2cp.MessageStatusMessage;

import org.junit.Test;

public class I2PSocketExceptionTest {
    private static Map<Integer, String> statusMap;

    static {
        Map<Integer, String> map = new HashMap<Integer, String>();
        map.put(MessageStatusMessage.STATUS_SEND_BEST_EFFORT_FAILURE, "Message timeout");
        map.put(MessageStatusMessage.STATUS_SEND_GUARANTEED_FAILURE, "Message timeout");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL, "Failed delivery to local destination");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_ROUTER, "Local router failure");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_NETWORK, "Local network failure");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_BAD_SESSION, "Session closed");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_BAD_MESSAGE, "Invalid message");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_BAD_OPTIONS, "Invalid message options");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_OVERFLOW, "Buffer overflow");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED, "Message expired");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_LOCAL_LEASESET, "Local lease set invalid");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_NO_TUNNELS, "No local tunnels");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_UNSUPPORTED_ENCRYPTION, "Unsupported encryption options");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_DESTINATION, "Invalid destination");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_BAD_LEASESET, "Local router failure");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_EXPIRED_LEASESET, "Destination lease set expired");
        map.put(MessageStatusMessage.STATUS_SEND_FAILURE_NO_LEASESET, "Destination lease set not found");
        map.put(SendMessageStatusListener.STATUS_CANCELLED, "Local destination shutdown");
        map.put(I2PSocketException.STATUS_CONNECTION_RESET, "Connection was reset");
        statusMap = map;
    }

    @Test
    public void testKnownStatus() {
        for (Map.Entry<Integer, String> entry : statusMap.entrySet()) {
            int status = entry.getKey();
            String msg = entry.getValue();
            I2PSocketException e = new I2PSocketException(status);
            assertThat(e.getStatus(), is(status));
            assertThat(e.getMessage(), is(msg));
        }
    }

    @Test
    public void testCustomStatus() {
        I2PSocketException e = new I2PSocketException("foo");
        assertThat(e.getStatus(), is(-1));
        assertThat(e.getMessage(), is("foo"));
    }

    @Test
    public void testUnknownStatus() {
        I2PSocketException e = new I2PSocketException(255);
        assertThat(e.getStatus(), is(255));
        assertThat(e.getMessage(), endsWith(": 255"));
    }
}
