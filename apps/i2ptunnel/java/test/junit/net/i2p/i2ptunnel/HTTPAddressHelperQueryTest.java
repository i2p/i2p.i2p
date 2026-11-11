package net.i2p.i2ptunnel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

public class HTTPAddressHelperQueryTest {
    @Test
    public void encodedHelperKeyAndBase64ValueRemainCompatible() throws Exception {
        assertArrayEquals(new String[] { "before=1&after=2", "abc+def/ghi=~" }, remove(
                "before=1&%69%32%70addresshelper=abc+def%2Fghi%3D%7E&after=2"));
    }

    @Test
    public void unrelatedBinaryRemoteQueriesArePreserved() throws Exception {
        assertNull(remove("opaque=%FF&binary%FF=value"));
        assertArrayEquals(new String[] { "opaque=%FF&binary%FF=value", "name.i2p" },
                remove("opaque=%FF&binary%FF=value&i2paddresshelper=name.i2p"));
    }

    @Test
    public void malformedHelperValueIsRejected() throws Exception {
        try {
            remove("i2paddresshelper=example.i2p%FF");
            fail("Accepted malformed helper");
        } catch (IllegalArgumentException expected) {}
    }

    private static String[] remove(String query) throws Exception {
        Method method = I2PTunnelHTTPClient.class.getDeclaredMethod("removeHelper", String.class);
        method.setAccessible(true);
        try {
            return (String[]) method.invoke(null, query);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalArgumentException)
                throw (IllegalArgumentException) e.getCause();
            throw e;
        }
    }
}
