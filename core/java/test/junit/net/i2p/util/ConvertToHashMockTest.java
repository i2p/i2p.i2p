package net.i2p.util;

import net.i2p.TestContext;
import net.i2p.client.naming.NamingService;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import org.junit.AfterClass;
import org.junit.Before;

import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

/**
 * @since 0.9.49
 */
public class ConvertToHashMockTest {

    @Mock private NamingService namingService;
    @Mock private Destination destination;
    @Mock private Hash hash;

    @InjectMocks TestContext testContext;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Reset the global context after all tests in the class are done.
     *
     * We would otherwise pollute the other tests that depend on I2PAppContext
     */
    @AfterClass
    public static void afterClass() {
        TestContext.setGlobalContext(null);
    }

    @Test
    public void testMockedDestination() {
        when(namingService.lookup("zzz.i2p")).thenReturn(destination);
        when(destination.calculateHash()).thenReturn(hash);

        assertSame(hash, ConvertToHash.getHash("zzz.i2p"));

        verify(namingService).lookup("zzz.i2p");
        verify(destination).calculateHash();
    }
}
