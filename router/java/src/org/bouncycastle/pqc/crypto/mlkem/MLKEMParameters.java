package org.bouncycastle.pqc.crypto.mlkem;

import org.bouncycastle.pqc.crypto.KEMParameters;

/**
 * @deprecated use org.bouncycastle.crypto.params.MLKEMParameters
 */
@Deprecated
public class MLKEMParameters
    implements KEMParameters
{
    public static final MLKEMParameters ml_kem_512 = new MLKEMParameters("ML-KEM-512", 2);
    public static final MLKEMParameters ml_kem_768 = new MLKEMParameters("ML-KEM-768", 3);
    public static final MLKEMParameters ml_kem_1024 = new MLKEMParameters("ML-KEM-1024", 4);

    private final String name;
    private final MLKEMEngine engine;

    private MLKEMParameters(String name, int k)
    {
        if (name == null)
        {
            throw new NullPointerException("'name' cannot be null");
        }

        this.name = name;
        this.engine = new MLKEMEngine(k);
    }

    MLKEMEngine getEngine()
    {
        return engine;
    }

    public int getEncapsulationLength()
    {
        return engine.getCipherTextBytes();
    }

    public String getName()
    {
        return name;
    }

    public int getSessionKeySize()
    {
        return 256;
    }
}
