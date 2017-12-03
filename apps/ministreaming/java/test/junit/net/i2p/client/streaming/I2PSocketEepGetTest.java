package net.i2p.client.streaming;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.Destination;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class I2PSocketEepGetTest {
    private static final String STATS_HOST = "stats.i2p";
    private static final String STATS_DEST = "Okd5sN9hFWx-sr0HH8EFaxkeIMi6PC5eGTcjM1KB7uQ0ffCUJ2nVKzcsKZFHQc7pLONjOs2LmG5H-2SheVH504EfLZnoB7vxoamhOMENnDABkIRGGoRisc5AcJXQ759LraLRdiGSR0WTHQ0O1TU0hAz7vAv3SOaDp9OwNDr9u902qFzzTKjUTG5vMTayjTkLo2kOwi6NVchDeEj9M7mjj5ySgySbD48QpzBgcqw1R27oIoHQmjgbtbmV2sBL-2Tpyh3lRe1Vip0-K0Sf4D-Zv78MzSh8ibdxNcZACmZiVODpgMj2ejWJHxAEz41RsfBpazPV0d38Mfg4wzaS95R5hBBo6SdAM4h5vcZ5ESRiheLxJbW0vBpLRd4mNvtKOrcEtyCvtvsP3FpA-6IKVswyZpHgr3wn6ndDHiVCiLAQZws4MsIUE1nkfxKpKtAnFZtPrrB8eh7QO9CkH2JBhj7bG0ED6mV5~X5iqi52UpsZ8gnjZTgyG5pOF8RcFrk86kHxAAAA";
    private static final String FETCH_URL = "http://" + STATS_HOST;
    private static final String FETCH_B64 = "http://i2p/" + STATS_DEST + "/";
    private static final String FETCH_BAD_B64 = "http://i2p/" + STATS_DEST;
    private static final String FETCH_RESULT_HEADER = "HTTP/1.1 200 OK\n" +
            "Date: Sun, 26 Jul 2015 00:00:00 GMT\n" +
            "Content-Length: 50\n" +
            "Last-Modified: Wed, 01 Jul 2015 13:37:00 GMT\n" +
            "Connection: close\n" +
            "Content-Type: text/html\n" +
            "Accept-Ranges: bytes\n" +
            "Cache-Control: max-age=3600,public\n" +
            "Proxy-Connection: close\n\n";
    private static final String FETCH_RESULT = "<html><head><title>stats.i2p</title></head></html>";

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock private I2PSocketManager mgr;
    @Mock private I2PSocketOptions opts;
    @Mock private I2PSocket sock;

    @BeforeClass
    public static void createHostsFile() throws IOException {
        String line = STATS_HOST + "=" + STATS_DEST + "\n";
        FileOutputStream out = new FileOutputStream("hosts.txt");
        try {
            out.write(line.getBytes());
        } finally {
            out.close();
        }
    }

    @AfterClass
    public static void deleteHostsFiles() {
        File f = new File("hosts.txt");
        f.delete();
        f = new File("hostsdb.blockfile");
        f.delete();
    }

    @Before
    public void setupMocks() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = new ByteArrayInputStream((FETCH_RESULT_HEADER + FETCH_RESULT).getBytes());

        when(mgr.buildOptions((Properties) any())).thenReturn(opts);
        when(mgr.connect((Destination) any(), eq(opts))).thenReturn(sock);
        when(sock.getOutputStream()).thenReturn(out);
        when(sock.getInputStream()).thenReturn(in);
    }

    public void fetchFrom(String url, boolean shouldSucceed) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        I2PSocketEepGet eep = new I2PSocketEepGet(I2PAppContext.getGlobalContext(),
                mgr, 0, -1, -1, null, baos, url);
        if (shouldSucceed) {
            assertTrue(eep.fetch());
            assertThat(baos.toString(), is(equalTo(FETCH_RESULT)));
        } else {
            assertFalse(eep.fetch());
            assertThat(baos.toString().length(), is(0));
        }
    }

    @Test
    public void testFetch() {
        fetchFrom(FETCH_URL, true);
    }

    @Test
    public void testFetch_withQuery() {
        fetchFrom(FETCH_URL + "?foo=bar", true);
    }

    @Test
    public void testFetch_malformedUrl() {
        fetchFrom(STATS_HOST, false);
    }

    @Test
    public void testFetch_unsupportedProto() {
        fetchFrom("https://" + STATS_HOST, false);
    }

    @Test
    public void testFetch_port() {
        fetchFrom("http://" + STATS_HOST + ":81", true);
    }

    @Test
    public void testFetch_negPort() {
        // Fails, because URI is stricter than URL
        fetchFrom("http://" + STATS_HOST + ":-1", false);
    }

    @Test
    public void testFetch_zeroPort() {
        // Gets rewritten to 80
        fetchFrom("http://" + STATS_HOST + ":0", true);
    }

    @Test
    public void testFetch_highPort() {
        // Gets rewritten to 80
        fetchFrom("http://" + STATS_HOST + ":90000", true);
    }

    @Test
    public void testFetch_B64() {
        fetchFrom(FETCH_B64, true);
    }

    @Test
    public void testFetch_badB64() {
        fetchFrom(FETCH_BAD_B64, false);
    }

    @Test
    public void testFetch_fakeUrl() {
        fetchFrom("http://example.i2p", false);
    }
}
