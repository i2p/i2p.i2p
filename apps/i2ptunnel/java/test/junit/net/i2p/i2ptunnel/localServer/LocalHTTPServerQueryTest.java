package net.i2p.i2ptunnel.localServer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.client.I2PSession;
import net.i2p.client.naming.NamingService;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.crypto.Blinding;
import net.i2p.crypto.SigType;
import net.i2p.data.BlindData;
import net.i2p.data.Certificate;
import net.i2p.data.Destination;
import net.i2p.data.PublicKey;
import net.i2p.data.SigningPublicKey;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class LocalHTTPServerQueryTest {
    @Test
    public void decodesUTF8IncludingSupplementaryCharacters() {
        assertEquals("é 日本 😀", LocalHTTPServer.decode("%C3%A9%20%E6%97%A5%E6%9C%AC%20%F0%9F%98%80"));
        assertEquals("é/é", LocalHTTPServer.decode("é%2f%C3%a9"));
    }

    @Test
    public void preservesLiteralPlusAndBase64Characters() {
        assertEquals("ab+c/d=~", LocalHTTPServer.decode("ab+c%2Fd%3D%7E"));
        assertEquals("a+b c", LocalHTTPServer.decode("a%2Bb%20c"));
        assertEquals("a+b", LocalHTTPServer.decode("a+b"));
    }

    @Test
    public void rejectsMalformedEscapesWithoutReturningAPrefix() {
        for (String value : new String[] { "foo%", "foo%2", "foo%ZZbar", "%+1", "%ＦＦ" })
            assertInvalid(value);
    }

    @Test
    public void rejectsInvalidAndOverlongUTF8() {
        for (String value : new String[] { "%80", "%C0%AF", "%C3", "%E2%28%A1", "%ED%A0%80", "%F4%90%80%80", "%FF" })
            assertInvalid(value);
    }

    @Test
    public void malformedTrailingParameterCannotSaveAddressBookEntry() throws Exception {
        I2PAppContext context = mock(I2PAppContext.class);
        I2PSocketManager manager = mock(I2PSocketManager.class);
        when(context.namingService()).thenReturn(mock(NamingService.class));
        String valid = "nonce=n+1&host=example.i2p&url=http%3A%2F%2Fexample.i2p%2F&dest=" + destination().toBase64();
        for (String suffix : new String[] { "&unused=%ZZ", "&unused=%C3", "&=%FF", "&bad%FF=value" }) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            LocalHTTPServer.serveLocalFile(context, manager, output, "GET", "/add", valid + suffix, "n+1", false);
            assertTrue(output.toString("UTF-8"), output.toString("UTF-8").startsWith("HTTP/1.1 400 "));
        }
        verifyNoInteractions(context, manager);
    }

    @Test
    public void malformedTrailingParameterCannotChangeBlindingCredentials() throws Exception {
        I2PAppContext context = mock(I2PAppContext.class);
        I2PSocketManager manager = mock(I2PSocketManager.class);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        when(context.clock()).thenReturn(I2PAppContext.getGlobalContext().clock());
        I2PSession session = mock(I2PSession.class);
        when(manager.getSession()).thenReturn(session);
        String host = Blinding.encode(new SigningPublicKey(SigType.EdDSA_SHA512_Ed25519, new byte[32]), true, false);
        String query = "nonce=n&host=" + host + "&url=http%3A%2F%2Fexample.i2p%2F&code=2&secret=password&later=%FF";
        LocalHTTPServer.serveLocalFile(context, manager, output, "GET", "/b32", query, "n", false);
        assertTrue(output.toString("UTF-8").startsWith("HTTP/1.1 400 "));
        verifyNoInteractions(context, manager, session);
    }

    @Test
    public void validInternationalAddressSubmissionRetainsURLAndDestination() throws Exception {
        I2PAppContext context = mock(I2PAppContext.class);
        NamingService naming = mock(NamingService.class);
        when(context.namingService()).thenReturn(naming);
        when(naming.put(anyString(), any(Destination.class), any(Properties.class))).thenReturn(true);
        Destination dest = destination();
        String query = "nonce=n+1;host=b%C3%BCcher.i2p&url=http%3A%2F%2Fexample.i2p%2F%3Fx%3Da%2Bb%26y%3Dc&dest=" + dest.toBase64() + "&local=1";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LocalHTTPServer.serveLocalFile(context, null, output, "GET", "/add", query, "n+1", false);
        ArgumentCaptor<Properties> options = ArgumentCaptor.forClass(Properties.class);
        verify(naming).put(eq("bücher.i2p"), eq(dest), options.capture());
        assertEquals("userhosts.txt", options.getValue().getProperty("list"));
        assertTrue(output.toString("UTF-8").startsWith("HTTP/1.1 200 "));
        assertTrue(output.toString("UTF-8").contains("http://example.i2p/?x=a+b&y=c"));
    }

    @Test
    public void validInternationalBlindingSecretRetainsLiteralPlus() throws Exception {
        I2PAppContext context = I2PAppContext.getGlobalContext();
        I2PSocketManager manager = mock(I2PSocketManager.class);
        I2PSession session = mock(I2PSession.class);
        when(manager.getSession()).thenReturn(session);
        String host = Blinding.encode(new SigningPublicKey(SigType.EdDSA_SHA512_Ed25519, new byte[32]), true, false);
        String query = "nonce=n&host=" + host + "&url=http%3A%2F%2Fexample.i2p%2F&code=2&secret=caf%C3%A9+%E6%97%A5";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LocalHTTPServer.serveLocalFile(context, manager, output, "GET", "/b32", query, "n", false);
        ArgumentCaptor<BlindData> data = ArgumentCaptor.forClass(BlindData.class);
        verify(session).sendBlindingInfo(data.capture());
        assertEquals("café+日", data.getValue().getSecret());
        assertTrue(output.toString("UTF-8").startsWith("HTTP/1.1 200 "));
    }

    private static void assertInvalid(String value) {
        try {
            LocalHTTPServer.decode(value);
            fail("Accepted malformed encoding: " + value);
        } catch (IllegalArgumentException expected) {}
    }

    private static Destination destination() {
        Destination destination = new Destination();
        destination.setPublicKey(new PublicKey(new byte[256]));
        destination.setSigningPublicKey(new SigningPublicKey(new byte[128]));
        destination.setCertificate(Certificate.NULL_CERT);
        return destination;
    }
}
