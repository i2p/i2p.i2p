
package net.i2p.aum;

import java.lang.*;
import java.io.*;

import net.i2p.*;
import net.i2p.client.*;
import net.i2p.data.*;

import net.i2p.*;
import net.i2p.client.*;
import net.i2p.client.streaming.*;
import net.i2p.data.Base64;
import net.i2p.util.*;
import net.i2p.data.*;

/**
 * A convenience class for encapsulating and manipulating I2P private keys
 */

public class PrivDestination
    //extends ByteArrayInputStream
    extends DataStructureImpl
{
    protected byte [] _bytes;

    protected Destination _dest;
    protected PrivateKey _privKey;
    protected SigningPrivateKey _signingPrivKey;
    
    protected static Log _log;
    
    /**
     * Create a PrivDestination object.
     * In most cases, you'll probably want to skip this constructor,
     * and create PrivDestination objects by invoking the desired static methods
     * of this class.
     * @param raw an array of bytes containing the raw binary private key
     */    
    public PrivDestination(byte [] raw) throws DataFormatException, IOException
    {
        //super(raw);
        _log = new Log("PrivDestination");

        _bytes = raw;
        readBytes(getInputStream());
    }

    /**
     * reconstitutes a PrivDestination from previously exported Base64
     */
    public PrivDestination(String b64) throws DataFormatException, IOException {
        this(Base64.decode(b64));
    }

    /**
     * generates a new PrivDestination with random keys
     */
    public PrivDestination() throws I2PException, IOException
    {
        I2PClient client = I2PClientFactory.createClient();
    
        ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
    
        // create a dest
        client.createDestination(streamOut);

        _bytes = streamOut.toByteArray();
        readBytes(getInputStream());
        
        // construct from the stream
        //return new PrivDestination(streamOut.toByteArray());
    }

    /** return the public Destination object for this private dest */
    public Destination getDestination() {
        return _dest;
    }

    /** return a PublicKey (encryption public key) object for this priv dest */
    public PublicKey getPublicKey() {
        return getDestination().getPublicKey();
    }

    /** return a PrivateKey (encryption private key) object for this priv dest */
    public PrivateKey getPrivateKey() {
        return _privKey;
    }

    /** return a SigningPublicKey object for this priv dest */
    public SigningPublicKey getSigningPublicKey() {
        return getDestination().getSigningPublicKey();
    }

    /** return a SigningPrivateKey object for this priv dest */
    public SigningPrivateKey getSigningPrivateKey() {
        return _signingPrivKey;
    }

    // static methods returning an instance
    
    /**
     * Creates a PrivDestination object
     * @param base64 a string containing the base64 private key data
     * @return a PrivDestination object encapsulating that key
     */
    public static PrivDestination fromBase64String(String base64)
        throws DataFormatException, IOException
    {
        return new PrivDestination(Base64.decode(base64));
    }
    
    /**
     * Creates a PrivDestination object, from the base64 key data
     * stored in a file.
     * @param path the pathname of the file from which to read the base64 private key data
     * @return a PrivDestination object encapsulating that key
     */
    public static PrivDestination fromBase64File(String path)
        throws FileNotFoundException, IOException, DataFormatException
    {
        return fromBase64String(new SimpleFile(path, "r").read());
        /*
        File f = new File(path);
        char [] rawchars = new char[(int)(f.length())];
        byte [] rawbytes = new byte[(int)(f.length())];
        FileReader fr = new FileReader(f);
        fr.read(rawchars);
        String raw64 = new String(rawchars);
        return PrivDestination.fromBase64String(raw64);
        */
    }
    
    /**
     * Creates a PrivDestination object, from the binary key data
     * stored in a file.
     * @param path the pathname of the file from which to read the binary private key data
     * @return a PrivDestination object encapsulating that key
     */
    public static PrivDestination fromBinFile(String path)
        throws FileNotFoundException, IOException, DataFormatException
    {
        byte [] raw = new SimpleFile(path, "r").readBytes();
        return new PrivDestination(raw);
    }
    
    /**
     * Generate a new random I2P private key
     * @return a PrivDestination object encapsulating that key
     */
    public static PrivDestination newKey() throws I2PException, IOException
    {
        return new PrivDestination();
    }
    
    public ByteArrayInputStream getInputStream()
    {
        return new ByteArrayInputStream(_bytes);
    }

    /**
     * Exports the key's full contents to a string
     * @return A base64-format string containing the full contents
     * of this private key. The string can be used in any subsequent
     * call to the .fromBase64String static constructor method.
     */
/*
    public String toBase64()
    {
        return Base64.encode(_bytes);
    }
*/

    /**
     * Exports the key's full contents to a byte array
     * @return A byte array containing the full contents
     * of this private key.
     */
/*
    public byte [] toBytes()
    {
        return _bytes;
    }
*/

    /**
     * Converts this key to a public destination.
     * @return a standard I2P Destination object containing the
     * public portion of this private key.
     */
     /*
    public Destination toDestination() throws DataFormatException
    {
        Destination dest = new Destination();
        dest.readBytes(_bytes, 0);
        return dest;
    }
    */

    /**
     * Converts this key to a base64 string representing a public destination
     * @return a string containing a base64 representation of the destination
     * corresponding to this private key.
     */
    public String getDestinationBase64() throws DataFormatException
    {
        return getDestination().toBase64();
    }
    
    public void readBytes(java.io.InputStream strm)
        throws net.i2p.data.DataFormatException, java.io.IOException
    {
        _dest = new Destination();
        _privKey = new PrivateKey();
        _signingPrivKey = new SigningPrivateKey();

        _dest.readBytes(strm);
        _privKey.readBytes(strm);
        _signingPrivKey.readBytes(strm);
    }
    
    public void writeBytes(java.io.OutputStream outputStream)
        throws net.i2p.data.DataFormatException, java.io.IOException
    {
        _dest.writeBytes(outputStream);
        _privKey.writeBytes(outputStream);
        _signingPrivKey.writeBytes(outputStream);
    }
    
}

