package net.i2p.router.transport.udp;

import java.io.IOException;
import java.util.Collections;

import org.junit.Test;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class SSU2PayloadTest {
    @Test
    public void truncatedPeerTestHashesAndBodiesAreRejected() {
        SSU2Payload.PayloadCallback callback = mock(SSU2Payload.PayloadCallback.class);
        for (int message : new int[] { 2, 4 }) {
            for (int length = 19; length < 19 + Hash.HASH_LENGTH; length++) {
                byte[] payload = new byte[SSU2Payload.BLOCK_HEADER_SIZE + length];
                payload[0] = 10;
                DataHelper.toLong(payload, 1, 2, length);
                payload[3] = (byte) message;
                assertThrows("Message " + message + " length " + length, IOException.class,
                             () -> SSU2Payload.processPayload(I2PAppContext.getGlobalContext(), callback,
                                                              payload, 0, payload.length, false, null));
            }
        }
        verifyNoInteractions(callback);
    }

    @Test
    public void peerTestCannotReadHashFromTheNextBlock() {
        SSU2Payload.PayloadCallback callback = mock(SSU2Payload.PayloadCallback.class);
        byte[] payload = new byte[128];
        payload[0] = 10;
        DataHelper.toLong(payload, 1, 2, 19);
        payload[3] = 2;
        // Bytes following this block are not part of its hash or test data.
        payload[22] = (byte) 254;
        DataHelper.toLong(payload, 23, 2, payload.length - 25);
        assertThrows(IOException.class,
                     () -> SSU2Payload.processPayload(I2PAppContext.getGlobalContext(), callback,
                                                      payload, 0, payload.length, false, null));
        verifyNoInteractions(callback);
    }

    @Test
    public void validPeerTestsRetainTheirHashAndData() throws Exception {
        for (int message = 1; message <= 7; message++) {
            for (int addressLength : new int[] { 6, 18 }) {
                SSU2Payload.PayloadCallback callback = mock(SSU2Payload.PayloadCallback.class);
                Hash hash = message == 2 || message == 4 ? Hash.create(new byte[32]) : null;
                byte[] data = new byte[10 + addressLength];
                data[0] = 2;
                data[9] = (byte) addressLength;
                SSU2Payload.PeerTestBlock block = new SSU2Payload.PeerTestBlock(message, 0, hash, data);
                byte[] payload = new byte[5 + block.getTotalLength()];
                int end = SSU2Payload.writePayload(payload, 5, Collections.singletonList(block));
                assertEquals(1, SSU2Payload.processPayload(I2PAppContext.getGlobalContext(), callback,
                                                          payload, 5, end - 5, false, null));
                verify(callback).gotPeerTest(eq(message), eq(0), eq(hash), eq(data));
                verifyNoMoreInteractions(callback);
            }
        }
    }
}
