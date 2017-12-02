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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

/**
 * @author Comwiz
 */
public class DataStructureImplTest {
    DataStructure _struct;

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Before
    public void setUp(){
        _struct = new DataStructureImpl(){
            private int x = 0;
            public void writeBytes(OutputStream out) throws IOException, DataFormatException{
                if(x++==0)
                    throw new DataFormatException("let it enfold you", new Exception());
                else
                    throw new IOException();
            }
            public void readBytes(InputStream in) throws IOException{
                throw new IOException();
            }
        };
    }

    @Test
    public void toBase64ReturnsNull() throws Exception{
        assertNull(_struct.toBase64());
    }

    @Test
    public void fromBase64ThrowsOnNull() throws Exception{
        exception.expect(DataFormatException.class);
        exception.expectMessage("Null data passed in");
        _struct.fromBase64(null);
    }

    @Test
    public void calculateHashReturnsNull() throws Exception{
        assertNull(_struct.calculateHash());
    }

    @Test
    public void fromByteArrayThrowsOnNull() throws Exception{
        exception.expect(DataFormatException.class);
        exception.expectMessage("Null data passed in");
        _struct.fromByteArray(null);
    }

    @Test
    public void fromByteArrayThrowsOnError() throws Exception{
        exception.expect(DataFormatException.class);
        exception.expectMessage("Error reading the byte array");
        _struct.fromByteArray(DataHelper.getASCII("water is poison"));
    }

    @Test
    public void toByteArrayReturnsNullOnError() throws Exception{
        assertNull(_struct.toByteArray());
    }
}
