package net.i2p.client.streaming.impl;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

/**
 * Stress test the MessageInputStream
 */
public class MessageInputStreamTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private I2PAppContext _context;
    private Log _log;
    private ConnectionOptions _options;
    private MessageInputStream in;

    @Mock private PacketLocal packetLocal;

    @Before
    public void setUp() {
        _context = I2PAppContext.getGlobalContext();
        _log = _context.logManager().getLog(MessageInputStreamTest.class);
        _options = new ConnectionOptions();

        in = new MessageInputStream(_context, _options.getMaxMessageSize(),
                _options.getMaxWindowSize(), _options.getInboundBufferSize());
    }

    @After
    public void tearDown() {
        in.close();
    }

    @Test
    public void testGetHighestReadyBlockId() {
        assertThat(in.getHighestReadyBlockId(), is((long) -1));
        in.messageReceived(0, new ByteArray());
        assertThat(in.getHighestReadyBlockId(), is((long) 0));
        in.messageReceived(2, new ByteArray());
        assertThat(in.getHighestReadyBlockId(), is((long) 0));
        in.messageReceived(1, new ByteArray());
        assertThat(in.getHighestReadyBlockId(), is((long) 2));
    }

    @Test
    public void testGetHighestBlockId() {
        assertThat(in.getHighestBlockId(), is((long) -1));
        in.messageReceived(0, new ByteArray());
        assertThat(in.getHighestBlockId(), is((long) 0));
        in.messageReceived(2, new ByteArray());
        assertThat(in.getHighestBlockId(), is((long) 2));
        in.messageReceived(1, new ByteArray());
        assertThat(in.getHighestBlockId(), is((long) 2));
    }

    @Test
    public void testCanAccept() {
        // Can always accept packets with no data
        assertTrue(in.canAccept(0, 0));
        assertTrue(in.canAccept(0, -1));

        // Can always accept packets with message ID < MIN_READY_BUFFERS (= 16)
        assertTrue(in.canAccept(0, 1024));
        assertTrue(in.canAccept(1, 1024));
        assertTrue(in.canAccept(15, 1024));
    }

    @Test
    public void testCanAccept_locallyClosed() {
        // Close
        in.close();
        assertTrue(in.isLocallyClosed());
        // Check that new messages will not be accepted
        assertFalse(in.canAccept(2, 1));
    }

    @Test
    public void testCanAccept_allMaxSize() {
        // Fill the buffer to one message under limit with max-size msgs
        int numMsgs = _options.getInboundBufferSize() / _options.getMaxMessageSize();
        byte orig[] = new byte[_options.getInboundBufferSize()];
        _context.random().nextBytes(orig);
        for (int i = 0; i < numMsgs - 1; i++) {
            byte msg[] = new byte[_options.getMaxMessageSize()];
            System.arraycopy(orig, i*_options.getMaxMessageSize(), msg, 0, _options.getMaxMessageSize());
            in.messageReceived(i, new ByteArray(msg));
        }
        assertTrue(in.canAccept(numMsgs, 1));
        byte msg[] = new byte[_options.getMaxMessageSize()];
        System.arraycopy(orig, (numMsgs-1)*_options.getMaxMessageSize(), msg, 0, _options.getMaxMessageSize());
        in.messageReceived(numMsgs - 1, new ByteArray(msg));
        assertFalse(in.canAccept(numMsgs, 1));
    }

    @Test
    public void testCanAccept_smallerMsgsWithMaxSizeCount() {
        // Fill the buffer to one message under count that would reach limit with max-size msgs
        int numMsgs = _options.getInboundBufferSize() / _options.getMaxMessageSize();
        byte orig[] = new byte[numMsgs*1024];
        _context.random().nextBytes(orig);
        for (int i = 0; i < numMsgs - 1; i++) {
            byte msg[] = new byte[1024];
            System.arraycopy(orig, i*1024, msg, 0, 1024);
            in.messageReceived(i, new ByteArray(msg));
        }
        assertTrue(in.canAccept(numMsgs, 1));
        byte msg[] = new byte[1024];
        System.arraycopy(orig, (numMsgs-1)*1024, msg, 0, 1024);
        in.messageReceived(numMsgs - 1, new ByteArray(msg));
        assertTrue(in.canAccept(numMsgs, 1));
    }

    @Test
    public void testCanAccept_readyDup() {
        // Fill the buffer
        int numMsgs = _options.getInboundBufferSize() / _options.getMaxMessageSize();
        byte orig[] = new byte[_options.getInboundBufferSize()];
        _context.random().nextBytes(orig);
        for (int i = 0; i < numMsgs; i++) {
            byte msg[] = new byte[_options.getMaxMessageSize()];
            System.arraycopy(orig, i*_options.getMaxMessageSize(), msg, 0, _options.getMaxMessageSize());
            in.messageReceived(i, new ByteArray(msg));
        }
        // Check that new messages won't be accepted
        assertFalse(in.canAccept(numMsgs, 1));
        // Check that duplicate messages will be accepted
        assertTrue(in.canAccept(numMsgs-1, 1));
    }

    @Test
    public void testCanAccept_notReadyDup() {
        // Fill the buffer
        int numMsgs = _options.getInboundBufferSize() / _options.getMaxMessageSize();
        byte orig[] = new byte[_options.getInboundBufferSize()];
        _context.random().nextBytes(orig);
        for (int i = 0; i < numMsgs; i++) {
            byte msg[] = new byte[_options.getMaxMessageSize()];
            System.arraycopy(orig, i*_options.getMaxMessageSize(), msg, 0, _options.getMaxMessageSize());
            if (i == numMsgs-1)
                in.messageReceived(numMsgs, new ByteArray(msg));
            else
                in.messageReceived(i, new ByteArray(msg));
        }
        // Check that duplicate notReady messages will be accepted
        assertTrue(in.canAccept(numMsgs, 1));
    }

    @Test
    public void testCanAccept_msgIdExceedsBuffer() {
        // Fill the buffer to one message under limit with max-size msgs
        int numMsgs = _options.getInboundBufferSize() / _options.getMaxMessageSize();
        byte orig[] = new byte[_options.getInboundBufferSize()];
        _context.random().nextBytes(orig);
        for (int i = 0; i < numMsgs - 2; i++) {
            byte msg[] = new byte[_options.getMaxMessageSize()];
            System.arraycopy(orig, i*_options.getMaxMessageSize(), msg, 0, _options.getMaxMessageSize());
            in.messageReceived(i, new ByteArray(msg));
        }
        // Add two half-size messages (to get past shortcut)
        byte msg[] = new byte[_options.getMaxMessageSize()/2];
        System.arraycopy(orig, (numMsgs-1)*_options.getMaxMessageSize(), msg, 0, _options.getMaxMessageSize()/2);
        in.messageReceived(numMsgs - 2, new ByteArray(msg));
        in.messageReceived(numMsgs - 1, new ByteArray(msg));
        // Check that it predicts only one more msgId will be accepted
        assertTrue(in.canAccept(numMsgs, 1));
        assertFalse(in.canAccept(numMsgs+1, 1));
    }

    @Test
    public void testCanAccept_inOrderSmallMsgsDoS() {
        // Fill the buffer to one message under count that would trip DoS protection
        int numMsgs = 4 * _options.getMaxWindowSize();
        byte orig[] = new byte[numMsgs];
        _context.random().nextBytes(orig);
        for (int i = 0; i < numMsgs - 1; i++) {
            byte msg[] = new byte[1];
            System.arraycopy(orig, i, msg, 0, 1);
            in.messageReceived(i, new ByteArray(msg));
        }
        assertTrue(in.canAccept(numMsgs, 1));
        // Trip DoS protection
        byte msg[] = new byte[1];
        System.arraycopy(orig, (numMsgs-1), msg, 0, 1);
        in.messageReceived(numMsgs - 1, new ByteArray(msg));
        assertFalse(in.canAccept(numMsgs, 1));
    }

    @Test
    public void testGetNacks() {
        assertThat(in.getNacks(), is(nullValue()));
        in.messageReceived(0, new ByteArray());
        assertThat(in.getNacks(), is(nullValue()));
        in.messageReceived(2, new ByteArray());
        assertThat(in.getNacks(), is(equalTo(new long[] {1})));
        in.messageReceived(4, new ByteArray());
        assertThat(in.getNacks(), is(equalTo(new long[] {1, 3})));
        in.messageReceived(1, new ByteArray());
        assertThat(in.getNacks(), is(equalTo(new long[] {3})));
        in.messageReceived(3, new ByteArray());
        assertThat(in.getNacks(), is(nullValue()));
    }

    @Test
    public void testUpdateAcks_noMsgs() {
        in.updateAcks(packetLocal);
        verify(packetLocal).setAckThrough(-1);
        verify(packetLocal).setNacks(null);
    }

    @Test
    public void testUpdateAcks_inOrderMsgs() {
        in.messageReceived(0, new ByteArray());
        in.messageReceived(1, new ByteArray());
        in.messageReceived(2, new ByteArray());
        in.updateAcks(packetLocal);
        verify(packetLocal).setAckThrough(2);
        verify(packetLocal).setNacks(null);
    }

    @Test
    public void testUpdateAcks_missingMsgs() {
        in.messageReceived(0, new ByteArray());
        in.messageReceived(2, new ByteArray());
        in.updateAcks(packetLocal);
        verify(packetLocal).setAckThrough(2);
        verify(packetLocal).setNacks(new long[] {1});
    }

    @Test
    public void testReadTimeout() {
        assertThat(in.getReadTimeout(), is(-1));
        in.setReadTimeout(100);
        assertThat(in.getReadTimeout(), is(100));
    }

    @Test
    public void testInOrder() throws IOException {
        byte orig[] = new byte[256*1024];
        _context.random().nextBytes(orig);

        for (int i = 0; i < orig.length / 1024; i++) {
            byte msg[] = new byte[1024];
            System.arraycopy(orig, i*1024, msg, 0, 1024);
            in.messageReceived(i, new ByteArray(msg));
        }

        byte read[] = new byte[orig.length];
        int howMany = DataHelper.read(in, read);
        if (howMany != orig.length)
            fail("not enough bytes read [" + howMany + "]");
        if (!DataHelper.eq(orig, read))
            fail("data read is not equal");

        _log.info("Passed test: in order");
    }

    @Test
    public void testRandomOrder() throws IOException {
        byte orig[] = new byte[256*1024];
        _context.random().nextBytes(orig);

        ArrayList<Integer> order = new ArrayList<Integer>(32);
        for (int i = 0; i < orig.length / 1024; i++)
            order.add(new Integer(i));
        Collections.shuffle(order);
        for (int i = 0; i < orig.length / 1024; i++) {
            byte msg[] = new byte[1024];
            Integer cur = (Integer)order.get(i);
            System.arraycopy(orig, cur.intValue()*1024, msg, 0, 1024);
            in.messageReceived(cur.intValue(), new ByteArray(msg));
            _log.debug("Injecting " + cur);
        }

        byte read[] = new byte[orig.length];
        int howMany = DataHelper.read(in, read);
        if (howMany != orig.length)
            fail("not enough bytes read [" + howMany + "]");
        if (!DataHelper.eq(orig, read))
            fail("data read is not equal");

        _log.info("Passed test: random order");
    }

    @Test
    public void testRandomDups() throws IOException {
        byte orig[] = new byte[256*1024];
        _context.random().nextBytes(orig);

        for (int n = 0; n < 3; n++) {
            ArrayList<Integer> order = new ArrayList<Integer>(32);
            for (int i = 0; i < orig.length / 1024; i++)
                order.add(new Integer(i));
            Collections.shuffle(order);
            for (int i = 0; i < orig.length / 1024; i++) {
                byte msg[] = new byte[1024];
                Integer cur = (Integer)order.get(i);
                System.arraycopy(orig, cur.intValue()*1024, msg, 0, 1024);
                in.messageReceived(cur.intValue(), new ByteArray(msg));
                _log.debug("Injecting " + cur);
            }
        }

        byte read[] = new byte[orig.length];
        int howMany = DataHelper.read(in, read);
        if (howMany != orig.length)
            fail("not enough bytes read [" + howMany + "]");
        if (!DataHelper.eq(orig, read))
            fail("data read is not equal");

        _log.info("Passed test: random dups");
    }

    @Test
    public void testStaggered() throws IOException {
        byte orig[] = new byte[256*1024];
        byte read[] = new byte[orig.length];
        _context.random().nextBytes(orig);

        ArrayList<Integer> order = new ArrayList<Integer>(32);
        for (int i = 0; i < orig.length / 1024; i++)
            order.add(new Integer(i));
        Collections.shuffle(order);

        int offset = 0;
        for (int i = 0; i < orig.length / 1024; i++) {
            byte msg[] = new byte[1024];
            Integer cur = (Integer)order.get(i);
            System.arraycopy(orig, cur.intValue()*1024, msg, 0, 1024);
            in.messageReceived(cur.intValue(), new ByteArray(msg));
            _log.debug("Injecting " + cur);

            if (in.available() > 0) {
                int curRead = in.read(read, offset, read.length-offset);
                _log.debug("read " + curRead);
                if (curRead == -1)
                    fail("EOF with offset " + offset);
                else
                    offset += curRead;
            }
        }

        if (!DataHelper.eq(orig, read))
            fail("data read is not equal");

        _log.info("Passed test: staggered");
    }
}
