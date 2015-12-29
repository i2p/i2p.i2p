package net.i2p.client.streaming.impl;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.util.SimpleTimer2;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class ConnectionTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock private ConnectionManager manager;
    @Mock private I2PSession session;
    @Mock private SchedulerChooser chooser;
    @Mock private SimpleTimer2 timer;
    @Mock private PacketQueue queue;
    @Mock private ConnectionPacketHandler handler;
    @Mock private ConnectionOptions opts;

    @Test
    public void test() {
        //Connection conn = new Connection(I2PAppContext.getGlobalContext(), manager, session, chooser, timer, queue, handler, opts, false);
    }
}
