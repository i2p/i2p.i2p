package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import junit.framework.TestCase;

/**
 * @author Comwiz
 */
public class DataStructureImplTest extends TestCase{
    DataStructure _struct;
    
    protected void setUp(){
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
    
    public void testNulls() throws Exception{
        assertNull(_struct.toBase64());
        
        boolean error = false;
        try{
            _struct.fromBase64(null);
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
        
        assertNull(_struct.calculateHash());
        
        error = false;
        try{
            _struct.fromByteArray(null);
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
    }
    
    public void testErrors() throws Exception{
        boolean error = false;
        try{
            _struct.fromByteArray("water is poison".getBytes());
        }catch(DataFormatException dfe){
            error = true;
        }
        assertTrue(error);
        
        assertNull(_struct.toByteArray());
        assertNull(_struct.toByteArray());
    }
}
