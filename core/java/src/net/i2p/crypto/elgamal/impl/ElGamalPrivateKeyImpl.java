package net.i2p.crypto.elgamal.impl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.interfaces.DHPrivateKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPrivateKeySpec;

import static net.i2p.crypto.SigUtil.intToASN1;
import net.i2p.crypto.elgamal.ElGamalPrivateKey;
import static net.i2p.crypto.elgamal.impl.ElGamalPublicKeyImpl.spaceFor;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPrivateKeySpec;

public class ElGamalPrivateKeyImpl
    implements ElGamalPrivateKey, DHPrivateKey
{
    private static final long serialVersionUID = 4819350091141529678L;
        
    private BigInteger x;
    private ElGamalParameterSpec elSpec;

    protected ElGamalPrivateKeyImpl()
    {
    }

    public ElGamalPrivateKeyImpl(
        ElGamalPrivateKey    key)
    {
        this.x = key.getX();
        this.elSpec = key.getParameters();
    }

    public ElGamalPrivateKeyImpl(
        DHPrivateKey    key)
    {
        this.x = key.getX();
        this.elSpec = new ElGamalParameterSpec(key.getParams().getP(), key.getParams().getG());
    }
    
    public ElGamalPrivateKeyImpl(
        ElGamalPrivateKeySpec    spec)
    {
        this.x = spec.getX();
        this.elSpec = new ElGamalParameterSpec(spec.getParams().getP(), spec.getParams().getG());
    }

    public ElGamalPrivateKeyImpl(
        DHPrivateKeySpec    spec)
    {
        this.x = spec.getX();
        this.elSpec = new ElGamalParameterSpec(spec.getP(), spec.getG());
    }
    
    public ElGamalPrivateKeyImpl(
        BigInteger x,
        ElGamalParameterSpec elSpec)
    {
        this.x = x;
        this.elSpec = elSpec;
    }

    public ElGamalPrivateKeyImpl(
        PKCS8EncodedKeySpec spec)
    {
        throw new UnsupportedOperationException("todo");
        //this.x = spec.getX();
        //this.elSpec = new ElGamalParameterSpec(spec.getP(), spec.getG());
    }
    
    public String getAlgorithm()
    {
        return "ElGamal";
    }

    /**
     * return the encoding format we produce in getEncoded().
     *
     * @return the string "PKCS#8"
     */
    public String getFormat()
    {
        return "PKCS#8";
    }

    /**
     * Return a PKCS8 representation of the key. The sequence returned
     * represents a full PrivateKeyInfo object.
     *
     * @return a PKCS8 representation of the key.
     */
    public byte[] getEncoded()
    {
        byte[] pb = elSpec.getP().toByteArray();
        byte[] gb = elSpec.getG().toByteArray();
        byte[] xb = x.toByteArray();
        int seq3len = spaceFor(pb.length) + spaceFor(gb.length);
        int seq2len = 8 + spaceFor(seq3len);
        int seq1len = 3 + spaceFor(seq2len) + spaceFor(xb.length);
        int totlen = spaceFor(seq1len);
        byte[] rv = new byte[totlen];
        int idx = 0;
        // sequence 1
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seq1len);

        // version
        rv[idx++] = 0x02;
        rv[idx++] = 1;
        rv[idx++] = 0;

        // Algorithm Identifier
        // sequence 2
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seq2len);
        // OID: 1.3.14.7.2.1.1
        rv[idx++] = 0x06;
        rv[idx++] = 6;
        rv[idx++] = (1 * 40) + 3;
        rv[idx++] = 14;
        rv[idx++] = 7;
        rv[idx++] = 2;
        rv[idx++] = 1;
        rv[idx++] = 1;

        // params
        // sequence 3
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seq3len);
        // P
        // integer
        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, pb.length);
        System.arraycopy(pb, 0, rv, idx, pb.length);
        idx += pb.length;
        // G
        // integer
        rv[idx++] = 0x02;
        idx = intToASN1(rv, idx, gb.length);
        System.arraycopy(gb, 0, rv, idx, gb.length);
        idx += gb.length;

        // the key
        // octet string
        rv[idx++] = 0x04;
        idx = intToASN1(rv, idx, xb.length);
        // BC puts an integer in the bit string, we're not going to do that
        System.arraycopy(xb, 0, rv, idx, xb.length);
        return rv;
    }

    public ElGamalParameterSpec getParameters()
    {
        return elSpec;
    }

    public DHParameterSpec getParams()
    {
        return new DHParameterSpec(elSpec.getP(), elSpec.getG());
    }
    
    public BigInteger getX()
    {
        return x;
    }

    private void readObject(
        ObjectInputStream   in)
        throws IOException, ClassNotFoundException
    {
        x = (BigInteger)in.readObject();

        this.elSpec = new ElGamalParameterSpec((BigInteger)in.readObject(), (BigInteger)in.readObject());
    }

    private void writeObject(
        ObjectOutputStream  out)
        throws IOException
    {
        out.writeObject(this.getX());
        out.writeObject(elSpec.getP());
        out.writeObject(elSpec.getG());
    }
}
