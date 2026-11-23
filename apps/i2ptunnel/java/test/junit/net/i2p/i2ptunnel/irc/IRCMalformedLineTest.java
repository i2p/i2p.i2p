package net.i2p.i2ptunnel.irc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import net.i2p.client.streaming.I2PSocket;
import net.i2p.util.Log;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class IRCMalformedLineTest {
    @Test
    public void missingInboundMessageBodiesAreDropped() {
        for (String line : new String[] { "PRIVMSG", "PRIVMSG nick", "NOTICE", "NOTICE nick",
                ":server PRIVMSG nick", "@time=1 PRIVMSG nick", "@time=1 :server NOTICE nick" }) {
            assertNull(line, IRCFilter.inboundFilter(line, new StringBuffer(), null));
        }
    }

    @Test
    public void missingOutboundCommandsAndArgumentsAreDropped() {
        for (String line : new String[] { "@tag", "@tag ", "PART", "@tag PART", "PRIVMSG",
                "PRIVMSG nick", "NOTICE", "NOTICE nick" }) {
            assertNull(line, IRCFilter.outboundFilter(line, new StringBuffer(), null));
        }
    }

    @Test
    public void malformedCTCPFramesAreDroppedInBothDirections() {
        for (String body : new String[] { "\001", "\001\001", "x\001ACTION hi\001",
                ":\001ACTION hi\001suffix", ":\001ACTION hi", ":\001ACTION hi\001\001" }) {
            assertNull(body, IRCFilter.inboundFilter("@tag :server PRIVMSG nick " + body, new StringBuffer(), null));
            assertNull(body, IRCFilter.outboundFilter("PRIVMSG nick " + body, new StringBuffer(), null));
        }
    }

    @Test
    public void normalMessagesAndPingPartRewritesStillWork() {
        assertEquals(":server PRIVMSG nick :hello there", IRCFilter.inboundFilter(
                ":server PRIVMSG nick :hello there", new StringBuffer(), null));
        assertEquals("@tag PRIVMSG nick :hello there", IRCFilter.outboundFilter(
                "@tag PRIVMSG nick :hello there", new StringBuffer(), null));
        assertEquals("PRIVMSG nick :\001ACTION waves\001", IRCFilter.outboundFilter(
                "PRIVMSG nick :\001ACTION waves\001", new StringBuffer(), null));
        assertEquals("@tag PRIVMSG nick :\001ACTION waves happily\001", IRCFilter.outboundFilter(
                "@tag PRIVMSG nick :\001ACTION waves happily\001", new StringBuffer(), null));
        assertEquals("@tag USER nick 0 * :real name", IRCFilter.outboundFilter(
                "@tag USER nick 0 * :real name", new StringBuffer(), null));
        assertEquals("USER nick hostname localhost :real name", IRCFilter.outboundFilter(
                "USER nick private.host private.server :real name", new StringBuffer(), null));
        assertEquals("PART #room :leaving", IRCFilter.outboundFilter("PART #room :private reason", new StringBuffer(), null));
        StringBuffer pong = new StringBuffer();
        assertEquals("PING nonce", IRCFilter.outboundFilter("PING nonce server", pong, null));
        assertEquals("PONG server :nonce", IRCFilter.inboundFilter(":server PONG nick :nonce", pong, null));
    }

    @Test
    public void generatedShortLinesNeverThrow() {
        String[] tokens = { "", "@tag", ":server", "PRIVMSG", "NOTICE", "PART", "USER", "\001", ":\001\001", "nick" };
        for (String a : tokens) for (String b : tokens) for (String c : tokens) {
            String line = a + " " + b + " " + c;
            IRCFilter.inboundFilter(line, new StringBuffer(), null);
            IRCFilter.outboundFilter(line, new StringBuffer(), null);
        }
    }

    @Test
    public void inboundLoopForwardsValidLineAfterMalformedLines() throws Exception {
        Socket local = mock(Socket.class);
        I2PSocket remote = mock(I2PSocket.class);
        Log log = mock(Log.class);
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
        String input = "PRIVMSG nick\r\n@tag\r\n:server NOTICE nick :still connected\r\n";
        when(remote.getInputStream()).thenReturn(new ByteArrayInputStream(input.getBytes(StandardCharsets.ISO_8859_1)));
        when(local.getOutputStream()).thenReturn(forwarded);
        new IrcInboundFilter(local, remote, new StringBuffer(), log).run();
        assertEquals(":server NOTICE nick :still connected\r\n", forwarded.toString("ISO-8859-1"));
        verify(log, never()).error(anyString(), any(Throwable.class));
    }

    @Test
    public void outboundLoopForwardsValidLineAfterMalformedLines() throws Exception {
        Socket local = mock(Socket.class);
        I2PSocket remote = mock(I2PSocket.class);
        Log log = mock(Log.class);
        ByteArrayOutputStream forwarded = new ByteArrayOutputStream();
        String input = "@tag\r\nPART\r\nPRIVMSG nick\r\nPRIVMSG nick :still connected\r\n";
        when(local.getInputStream()).thenReturn(new ByteArrayInputStream(input.getBytes(StandardCharsets.ISO_8859_1)));
        when(remote.getOutputStream()).thenReturn(forwarded);
        new IrcOutboundFilter(local, remote, new StringBuffer(), log).run();
        assertEquals("PRIVMSG nick :still connected\r\n", forwarded.toString("ISO-8859-1"));
        verify(log, never()).error(anyString(), any(Throwable.class));
    }
}
