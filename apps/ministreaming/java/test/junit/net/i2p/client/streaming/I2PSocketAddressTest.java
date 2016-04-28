package net.i2p.client.streaming;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class I2PSocketAddressTest {
	private static final String STATS_HOST = "stats.i2p";
	private static final String STATS_DEST = "Okd5sN9hFWx-sr0HH8EFaxkeIMi6PC5eGTcjM1KB7uQ0ffCUJ2nVKzcsKZFHQc7pLONjOs2LmG5H-2SheVH504EfLZnoB7vxoamhOMENnDABkIRGGoRisc5AcJXQ759LraLRdiGSR0WTHQ0O1TU0hAz7vAv3SOaDp9OwNDr9u902qFzzTKjUTG5vMTayjTkLo2kOwi6NVchDeEj9M7mjj5ySgySbD48QpzBgcqw1R27oIoHQmjgbtbmV2sBL-2Tpyh3lRe1Vip0-K0Sf4D-Zv78MzSh8ibdxNcZACmZiVODpgMj2ejWJHxAEz41RsfBpazPV0d38Mfg4wzaS95R5hBBo6SdAM4h5vcZ5ESRiheLxJbW0vBpLRd4mNvtKOrcEtyCvtvsP3FpA-6IKVswyZpHgr3wn6ndDHiVCiLAQZws4MsIUE1nkfxKpKtAnFZtPrrB8eh7QO9CkH2JBhj7bG0ED6mV5~X5iqi52UpsZ8gnjZTgyG5pOF8RcFrk86kHxAAAA";

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

	@Test
	public void testConstruct_Host() {
		I2PSocketAddress addr = new I2PSocketAddress(STATS_HOST);
		assertThat(addr.getPort(), is(0));
		assertThat(addr.getAddress().toBase64(), is(equalTo(STATS_DEST)));
		assertThat(addr.getHostName(), is(equalTo(STATS_HOST)));
		assertFalse(addr.isUnresolved());
	}

	@Test
	public void testConstruct_Host_withPort() {
		I2PSocketAddress addr = new I2PSocketAddress(STATS_HOST + ":81");
		assertThat(addr.getPort(), is(81));
		assertThat(addr.getAddress().toBase64(), is(equalTo(STATS_DEST)));
		assertThat(addr.getHostName(), is(equalTo(STATS_HOST)));
		assertFalse(addr.isUnresolved());
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_Host_negPort_throwsIAE() {
		new I2PSocketAddress(STATS_HOST + ":-1");
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_Host_highPort_throwsIAE() {
		new I2PSocketAddress(STATS_HOST + ":90000");
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_Host_missingPort_throwsIAE() {
		new I2PSocketAddress(STATS_HOST + ":");
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_Host_notAPort_throwsIAE() {
		new I2PSocketAddress(STATS_HOST + ":spam");
	}

	@Test
	public void testConstruct_Dest() {
		Destination dest = new Destination();
		I2PSocketAddress addr = new I2PSocketAddress(dest, 1234);
		assertThat(addr.getPort(), is(1234));
		assertThat(addr.getAddress(), is(dest));
		assertThat(addr.getHostName(), is(nullValue()));
		assertFalse(addr.isUnresolved());
	}

	@Test(expected=NullPointerException.class)
	public void testConstruct_nullDest_throwsNPE() {
		new I2PSocketAddress((Destination)null, 1234);
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_Dest_negPort_throwsIAE() {
		new I2PSocketAddress(new Destination(), -1);
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_Dest_highPort_throwsIAE() {
		new I2PSocketAddress(new Destination(), 90000);
	}

	@Test
	public void testConstruct_HostPort() {
		I2PSocketAddress addr = new I2PSocketAddress(STATS_HOST, 81);
		assertThat(addr.getPort(), is(81));
		assertThat(addr.getAddress().toBase64(), is(equalTo(STATS_DEST)));
		assertThat(addr.getHostName(), is(equalTo(STATS_HOST)));
		assertFalse(addr.isUnresolved());
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_HostPort_negPort_throwsIAE() {
		new I2PSocketAddress(STATS_HOST, -1);
	}

	@Test(expected=IllegalArgumentException.class)
	public void testConstruct_HostPort_highPort_throwsIAE() {
		new I2PSocketAddress(STATS_HOST, 90000);
	}

	@Test
	public void testCreateUnresolved() {
		I2PSocketAddress addr = I2PSocketAddress.createUnresolved(STATS_HOST, 81);
		assertThat(addr.getPort(), is(81));
		assertThat(addr.getHostName(), is(STATS_HOST));
		assertTrue(addr.isUnresolved());
		assertThat(addr.getAddress().toBase64(), is(equalTo(STATS_DEST)));
		assertFalse(addr.isUnresolved());
	}

	@Test(expected=IllegalArgumentException.class)
	public void testCreateUnresolved_negPort_throwsIAE() {
		I2PSocketAddress.createUnresolved(STATS_HOST, -1);
	}

	@Test(expected=IllegalArgumentException.class)
	public void testCreateUnresolved_highPort_throwsIAE() {
		I2PSocketAddress.createUnresolved(STATS_HOST, 90000);
	}

	@Test
	public void testToString() throws Exception {
		Destination dest = I2PClientFactory.createClient().createDestination(new ByteArrayOutputStream());
		I2PSocketAddress addr = new I2PSocketAddress(dest, 1234);
		assertThat(addr.toString(), is(equalTo(dest.calculateHash().toString() + ":1234")));
	}

	@Test
	public void testToString_unresolved() {
		I2PSocketAddress addr = I2PSocketAddress.createUnresolved("example.i2p", 1234);
		assertThat(addr.toString(), is(equalTo("example.i2p:1234")));
	}

	@Test
	public void testEquals() {
		I2PSocketAddress addr = I2PSocketAddress.createUnresolved("example.i2p", 1234);
		assertTrue(addr.equals(I2PSocketAddress.createUnresolved("example.i2p", 1234)));
		assertFalse(addr.equals(I2PSocketAddress.createUnresolved("example2.i2p", 1234)));
		assertFalse(addr.equals(I2PSocketAddress.createUnresolved("example.i2p", 1235)));
		assertFalse(addr.equals(I2PSocketAddress.createUnresolved("example.i2p", 1235)));

		Destination dest = new Destination();
		I2PSocketAddress addr2 = new I2PSocketAddress(dest, 1234);
		assertFalse(addr.equals(null));
		assertFalse(addr.equals(dest));
		assertFalse(addr.equals(addr2));
		assertFalse(addr2.equals(addr));
	}
}
