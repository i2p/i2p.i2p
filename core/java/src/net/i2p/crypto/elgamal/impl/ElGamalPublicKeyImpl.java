package net.i2p.crypto.elgamal.impl;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.interfaces.DHPublicKey;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPublicKeySpec;

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
        return null;
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
