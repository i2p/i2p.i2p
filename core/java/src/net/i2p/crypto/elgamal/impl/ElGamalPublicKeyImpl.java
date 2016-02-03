package net.i2p.crypto.elgamal.impl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;

import static net.i2p.crypto.SigUtil.intToASN1;
import net.i2p.crypto.elgamal.ElGamalPublicKey;
import net.i2p.crypto.elgamal.spec.ElGamalParameterSpec;
import net.i2p.crypto.elgamal.spec.ElGamalPublicKeySpec;

public class ElGamalPublicKeyImpl
    implements ElGamalPublicKey, DHPublicKey
{
    private static final long serialVersionUID = 8712728417091216948L;
        
    private BigInteger              y;
    private ElGamalParameterSpec    elSpec;

    public ElGamalPublicKeyImpl(
        ElGamalPublicKeySpec    spec)
    {
        this.y = spec.getY();
        this.elSpec = new ElGamalParameterSpec(spec.getParams().getP(), spec.getParams().getG());
    }

    public ElGamalPublicKeyImpl(
        DHPublicKeySpec    spec)
    {
        this.y = spec.getY();
        this.elSpec = new ElGamalParameterSpec(spec.getP(), spec.getG());
    }
    
    public ElGamalPublicKeyImpl(
        ElGamalPublicKey    key)
    {
        this.y = key.getY();
        this.elSpec = key.getParameters();
    }

    public ElGamalPublicKeyImpl(
        DHPublicKey    key)
    {
        this.y = key.getY();
        this.elSpec = new ElGamalParameterSpec(key.getParams().getP(), key.getParams().getG());
    }
    
    public ElGamalPublicKeyImpl(
        BigInteger              y,
        ElGamalParameterSpec    elSpec)
    {
        this.y = y;
        this.elSpec = elSpec;
    }
    
    public ElGamalPublicKeyImpl(
        X509EncodedKeySpec spec)
    {
        throw new UnsupportedOperationException("todo");
        //this.y = y;
        //this.elSpec = elSpec;
    }

    public String getAlgorithm()
    {
        return "ElGamal";
    }

    public String getFormat()
    {
        return "X.509";
    }

    public byte[] getEncoded()
    {
        byte[] pb = elSpec.getP().toByteArray();
        byte[] gb = elSpec.getG().toByteArray();
        byte[] yb = y.toByteArray();
        int seq3len = spaceFor(pb.length) + spaceFor(gb.length);
        int seq2len = 8 + spaceFor(seq3len);
        int seq1len = spaceFor(seq2len) + spaceFor(yb.length + 1);
        int totlen = spaceFor(seq1len);
        byte[] rv = new byte[totlen];
        int idx = 0;
        // sequence 1
        rv[idx++] = 0x30;
        idx = intToASN1(rv, idx, seq1len);

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
        // bit string
        rv[idx++] = 0x03;
        idx = intToASN1(rv, idx, yb.length + 1);
        rv[idx++] = 0; // number of trailing unused bits
        // BC puts an integer in the bit string, we're not going to do that
        System.arraycopy(yb, 0, rv, idx, yb.length);
        return rv;
    }

    /**
     *  @param val the length of the value, 65535 max
     *  @return the length of the TLV
     */
    static int spaceFor(int val) {
        int rv;
        if (val > 255)
            rv = 3;
        else if (val > 127)
            rv = 2;
        else
            rv = 1;
        return 1 + rv + val;
    }

    public ElGamalParameterSpec getParameters()
    {
        return elSpec;
    }
    
    public DHParameterSpec getParams()
    {
        return new DHParameterSpec(elSpec.getP(), elSpec.getG());
    }

    public BigInteger getY()
    {
        return y;
    }

    private void readObject(
        ObjectInputStream   in)
        throws IOException, ClassNotFoundException
    {
        this.y = (BigInteger)in.readObject();
        this.elSpec = new ElGamalParameterSpec((BigInteger)in.readObject(), (BigInteger)in.readObject());
    }

    private void writeObject(
        ObjectOutputStream  out)
        throws IOException
    {
        out.writeObject(this.getY());
        out.writeObject(elSpec.getP());
        out.writeObject(elSpec.getG());
    }
}
