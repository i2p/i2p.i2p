package net.i2p.router.networkdb.reseed;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;

import net.i2p.I2PAppContext;
import net.i2p.crypto.SU3File;
import net.i2p.data.Hash;
import net.i2p.data.DataHelper;
import net.i2p.router.RouterContext;
import net.i2p.util.EepGet;
import net.i2p.util.SSLEepGet;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ReseedDecodedBoundsTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    private RouterContext context;
    private Object runner;
    private File temporary;
    private File routerDirectory;
    private long now;

    @Before
    public void setUp() throws Exception {
        context = mock(RouterContext.class, RETURNS_DEEP_STUBS);
        when(context.logManager()).thenReturn(I2PAppContext.getGlobalContext().logManager());
        when(context.random()).thenReturn(I2PAppContext.getGlobalContext().random());
        now = System.currentTimeMillis();
        when(context.clock().now()).thenReturn(now);
        temporary = directory.newFolder("temporary");
        routerDirectory = directory.newFolder("router");
        when(context.getTempDir()).thenReturn(temporary);
        when(context.getRouterDir()).thenReturn(routerDirectory);
        when(context.routerHash()).thenReturn(null);
        when(context.getProperty(eq(Reseeder.PROP_SPROXY_TYPE), anyString())).thenReturn("HTTP");
        runner = newRunner();
    }

    @Test
    public void oversizedHTTPSHostDoesNotBlockNextVerifiedHost() throws Exception {
        assertNextHostRuns("oversize");
    }


    @Test
    public void invalidSignatureDoesNotImportOrBlockNextVerifiedHost() throws Exception {
        assertNextHostRuns("signature");
    }

    private void assertNextHostRuns(String failure) throws Exception {
        String name = "routerInfo-" + Hash.create(new byte[32]).toBase64() + ".dat";
        byte[] expected = new byte[] { 1, 2, 3 };
        try (MockedConstruction<SSLEepGet> fetches = mockConstruction(SSLEepGet.class, (get, construction) -> {
            OutputStream out = output(construction.arguments());
            when(get.getStatusCode()).thenReturn(200);
            when(get.fetch()).thenAnswer(call -> {
                out.write(new byte[] { 9, 8, 7 });
                if (construction.getCount() == 1) {
                    if ("oversize".equals(failure)) {
                        try {
                            out.write(new byte[1024 * 1024]);
                            fail("An unknown-length HTTPS response must stop at the bundle limit");
                        } catch (IOException expectedFailure) {
                            assertTrue(expectedFailure.getMessage().contains("exceeds"));
                        }
                    }
                    return "signature".equals(failure);
                }
                return true;
            });
        }); MockedConstruction<SU3File> signatures = mockConstruction(SU3File.class, (su3, construction) -> {
            when(su3.getContentType()).thenReturn(SU3File.CONTENT_RESEED);
            when(su3.getVersionString()).thenReturn(Long.toString(now / 1000));
            when(su3.verifyAndMigrate(any(File.class))).thenAnswer(call -> {
                if ("signature".equals(failure) && construction.getCount() == 1)
                    return false;
                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(call.<File>getArgument(0)))) {
                    zip.putNextEntry(new ZipEntry(name));
                    zip.write(expected);
                    zip.closeEntry();
                }
                return true;
            });
        })) {
            Field running = runner.getClass().getDeclaredField("_isRunning");
            running.setAccessible(true);
            running.setBoolean(runner, true);
            Method reseed = runner.getClass().getDeclaredMethod("reseed", List.class, boolean.class);
            reseed.setAccessible(true);
            List<URI> hosts = new ArrayList<URI>(Arrays.asList(new URI("https://blocked.invalid/"),
                                                              new URI("https://working.invalid/")));
            assertEquals(1, ((Integer) reseed.invoke(runner, hosts, false)).intValue());
            assertEquals("Both independent hosts must be attempted", 2, fetches.constructed().size());
            for (SSLEepGet get : fetches.constructed())
                verify(get).fetch();
            assertEquals("Only fetched content may reach SU3 verification",
                         "signature".equals(failure) ? 2 : 1, signatures.constructed().size());
            for (SU3File su3 : signatures.constructed())
                verify(su3).verifyAndMigrate(any(File.class));
            File imported = new File(new File(routerDirectory, "netDb"), name);
            assertArrayEquals(expected, Files.readAllBytes(imported.toPath()));
            assertEquals("Partial and completed download files must be removed", 0, temporary.list().length);
            verify(context.netDb(), times(1)).rescan();
        }
    }

    @Test
    public void inMemoryHTTPSResponseIsBoundedWithoutAContentLength() throws Exception {
        try (MockedConstruction<SSLEepGet> fetches = mockConstruction(SSLEepGet.class, (get, construction) -> {
            OutputStream out = output(construction.arguments());
            when(get.getStatusCode()).thenReturn(200);
            when(get.fetch()).thenAnswer(call -> {
                out.write(new byte[2 * 1024 * 1024]);
                try {
                    out.write(1);
                    fail("The in-memory response limit must include single-byte writes");
                } catch (IOException expected) {
                    return false;
                }
                return true;
            });
        })) {
            Method read = runner.getClass().getDeclaredMethod("readURL", URI.class);
            read.setAccessible(true);
            assertNull(read.invoke(runner, new URI("https://blocked.invalid/")));
            verify(fetches.constructed().get(0)).fetch();
        }
    }

    @Test
    public void boundedHTTPSFetchPreservesProxyAuthenticationAndSSLState() throws Exception {
        when(context.getBooleanProperty(Reseeder.PROP_SPROXY_ENABLE)).thenReturn(true);
        when(context.getProperty(eq(Reseeder.PROP_SPROXY_TYPE), anyString())).thenReturn("SOCKS5");
        when(context.getProperty(Reseeder.PROP_SPROXY_HOST)).thenReturn("127.0.0.1");
        when(context.getProperty(eq(Reseeder.PROP_SPROXY_PORT), anyInt())).thenReturn(1080);
        when(context.getBooleanProperty(Reseeder.PROP_SPROXY_AUTH_ENABLE)).thenReturn(true);
        when(context.getProperty(Reseeder.PROP_SPROXY_USERNAME)).thenReturn("user");
        when(context.getProperty(Reseeder.PROP_SPROXY_PASSWORD)).thenReturn("password");
        runner = newRunner();
        SSLEepGet.SSLState sslState = mock(SSLEepGet.SSLState.class);
        try (MockedConstruction<SSLEepGet> fetches = mockConstruction(SSLEepGet.class, (get, construction) -> {
            List<?> args = construction.arguments();
            assertEquals(SSLEepGet.ProxyType.SOCKS5, args.get(1));
            assertEquals("127.0.0.1", args.get(2));
            assertEquals(1080, args.get(3));
            if (construction.getCount() > 1)
                assertSame(sslState, args.get(6));
            when(get.getSSLState()).thenReturn(sslState);
            when(get.getStatusCode()).thenReturn(200);
            when(get.fetch()).thenAnswer(call -> {
                output(args).write(new byte[] { 1, 2, 3 });
                return true;
            });
        })) {
            for (String host : new String[] { "one.invalid", "two.invalid" }) {
                File response = fetch(new URI("https://" + host + "/i2pseeds.su3"));
                assertEquals(3, response.length());
                assertTrue(response.delete());
            }
            for (SSLEepGet get : fetches.constructed())
                verify(get).addAuthorization("user", "password");
        }
    }

    @Test
    public void failedHTTPFetchAlsoRemovesPartialFile() throws Exception {
        try (MockedConstruction<EepGet> fetches = mockConstruction(EepGet.class, (get, construction) -> {
            when(get.fetch()).thenAnswer(call -> {
                output(construction.arguments()).write(new byte[] { 1, 2, 3 });
                return false;
            });
        })) {
            assertNull(fetch(new URI("http://blocked.invalid/i2pseeds.su3")));
            assertEquals(0, temporary.list().length);
            verify(fetches.constructed().get(0)).fetch();
        }
    }

    @Test
    public void decodedResponseLimitRejectsWholeOverrunWithoutWritingAPrefix() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream limited = new ReseedOutputStream(bytes, 4);
        limited.write(new byte[] { 1, 2, 3 });
        try {
            limited.write(new byte[] { 4, 5 });
            fail("The decoded output must not exceed the limit");
        } catch (IOException expected) {
            assertArrayEquals(new byte[] { 1, 2, 3 }, bytes.toByteArray());
        }
        limited.write(4);
        limited.write(new byte[0]);
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, bytes.toByteArray());
    }

    @Test
    public void compressedResponseCannotExpandBeyondDecodedLimit() throws Exception {
        int limit = 32*1024;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(new byte[limit + 1]);
        }
        assertTrue(compressed.size() < limit);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            try {
                DataHelper.copy(gzip, new ReseedOutputStream(decoded, limit));
                fail("A small compressed response must not bypass the decoded limit");
            } catch (IOException expected) {
                assertTrue(decoded.size() <= limit);
            }
        }
    }

    private Object newRunner() throws Exception {
        Reseeder reseeder = new Reseeder(context, mock(ReseedChecker.class));
        Class<?> type = Class.forName(Reseeder.class.getName() + "$ReseedRunner");
        Constructor<?> constructor = type.getDeclaredConstructor(Reseeder.class);
        constructor.setAccessible(true);
        return constructor.newInstance(reseeder);
    }

    private File fetch(URI uri) throws Exception {
        Method fetch = runner.getClass().getDeclaredMethod("fetchURL", URI.class);
        fetch.setAccessible(true);
        return (File) fetch.invoke(runner, uri);
    }

    private static OutputStream output(List<?> arguments) {
        for (Object argument : arguments) {
            if (argument instanceof OutputStream)
                return (OutputStream) argument;
        }
        throw new AssertionError("The fetch must use the bounded output stream");
    }
}
