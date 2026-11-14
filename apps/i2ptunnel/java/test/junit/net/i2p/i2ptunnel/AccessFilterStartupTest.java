package net.i2p.i2ptunnel;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.i2ptunnel.access.InvalidDefinitionException;
import net.i2p.util.EventDispatcher;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AccessFilterStartupTest {
    @Test
    public void missingConfiguredFilterStopsStartupBeforeReadingKeys() throws Exception {
        File directory = Files.createTempDirectory("missing-access-filter").toFile();
        try {
            reject(new File(directory, "missing.rules"), IOException.class);
        } finally {
            directory.delete();
        }
    }

    @Test
    public void malformedConfiguredFilterStopsStartupBeforeReadingKeys() throws Exception {
        File definition = File.createTempFile("invalid-access-filter", ".rules");
        try {
            Files.write(definition.toPath(), "invalid default\n".getBytes("UTF-8"));
            reject(definition, InvalidDefinitionException.class);
        } finally {
            definition.delete();
        }
    }

    @Test
    public void invalidThresholdStopsStartupBeforeReadingKeys() throws Exception {
        File definition = File.createTempFile("invalid-access-threshold", ".rules");
        try {
            Files.write(definition.toPath(), "2/0 default\n".getBytes("UTF-8"));
            reject(definition, InvalidDefinitionException.class);
        } finally {
            definition.delete();
        }
    }

    @Test
    public void validDenyFilterReachesSocketSetup() throws Exception {
        File definition = File.createTempFile("valid-access-filter", ".rules");
        Keys keys = new Keys();
        try {
            Files.write(definition.toPath(), "deny default\n".getBytes("UTF-8"));
            try {
                server(definition, keys, mock(Logging.class), mock(EventDispatcher.class));
                fail("Empty private key stream must fail");
            } catch (IllegalArgumentException expected) {
                assertTrue("Valid filter must reach key parsing", keys.reads > 0);
                assertTrue(keys.closed);
            }
        } finally {
            definition.delete();
        }
    }

    private static void reject(File definition, Class<?> cause) throws Exception {
        Keys keys = new Keys();
        Logging logging = mock(Logging.class);
        EventDispatcher dispatcher = mock(EventDispatcher.class);
        try {
            server(definition, keys, logging, dispatcher);
            fail("Invalid configured protection must stop server startup");
        } catch (IllegalArgumentException expected) {
            assertTrue("Expected configuration failure, got " + expected, cause.isInstance(expected.getCause()));
            assertTrue(expected.getMessage().contains(definition.toString()));
            assertTrue(keys.closed);
            assertEquals("Do not create a socket manager without the requested filter", 0, keys.reads);
            verify(logging).log(contains("Cannot start server with access filter"));
            verify(dispatcher).notifyEvent("openServerResult", "error");
        }
    }

    private static void server(File definition, Keys keys, Logging logging, EventDispatcher dispatcher) {
        I2PTunnel tunnel = mock(I2PTunnel.class);
        when(tunnel.getContext()).thenReturn(I2PAppContext.getGlobalContext());
        when(tunnel.getClientOptions()).thenReturn(new Properties());
        tunnel.filterDefinition = definition.toString();
        new I2PTunnelServer(InetAddress.getLoopbackAddress(), 8080, keys, "test keys", logging, dispatcher, tunnel);
    }

    private static class Keys extends InputStream {
        int reads;
        boolean closed;
        public int read() {
            reads++;
            return -1;
        }
        public void close() {
            closed = true;
        }
    }
}
