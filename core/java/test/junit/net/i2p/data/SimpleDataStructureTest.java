package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import static org.junit.Assert.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * Test harness for the simple data structure
 *
 * @author welterde
 */
public class SimpleDataStructureTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Test
    public void setDataThrowsOnNullAfterDataSet() throws Exception {
        // create new test subject
        TestStruct struct = new TestStruct();

        // try to set null object.. should not fail..
        struct.setData(null);

        // set data to something
        struct.setData(new byte[3]);

        // now setting it to null should fail
        exception.expect(RuntimeException.class);
        exception.expectMessage("Data already set");
        struct.setData(null);
    }

    @Test
    public void setDataThrowsOnDataAfterDataSet() throws Exception {
        // create new test subject
        TestStruct struct = new TestStruct();

        // try to set null object.. should not fail..
        struct.setData(null);

        // set data to something
        struct.setData(new byte[3]);

        // setting it to something non-null should fail.
        exception.expect(RuntimeException.class);
        exception.expectMessage("Data already set");
        struct.setData(new byte[3]);
    }

    @Test
    public void testReadBytesImmutable() throws Exception {
        // create new test subject
        TestStruct struct = new TestStruct();

        // load some data using setData
        struct.setData(new byte[3]);

        // now try to load via readBytes
        try {
            struct.readBytes(null);
            fail("blah blah blah..");
        } catch(RuntimeException exc) {
            // all good
        }
    }

    @Test
    public void testToBase64Safe() throws Exception {
        // create new test subject
        TestStruct struct = new TestStruct();

        // now try to get the Base64.. should not throw an exception, but should not be an empty string either
        assertNull(struct.toBase64());
    }

    @Test
    public void testCalculateHashSafe() throws Exception {
        // create new test subject
        TestStruct struct = new TestStruct();

        // now try to get the hash.. should not throw an exception
        assertNull(struct.calculateHash());
    }

    @Test
    public void testHashCodeSafe() throws Exception {
        // create new test subject
        TestStruct struct = new TestStruct();

        // just make sure it doesn't explode in our face
        struct.hashCode();
    }

    public class TestStruct extends SimpleDataStructure {
        public int length() {
            return 3;
        }
    }
}
