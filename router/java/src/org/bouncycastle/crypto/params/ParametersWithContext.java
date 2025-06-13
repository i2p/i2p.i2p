package org.bouncycastle.crypto.params;

import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.util.Arrays;

public class ParametersWithContext
    implements CipherParameters
{
    private CipherParameters  parameters;
    private byte[] context;

    public ParametersWithContext(
        CipherParameters parameters,
        byte[] context)
    {
        if (context == null)
        {
            throw new NullPointerException("'context' cannot be null");
        }

        this.parameters = parameters;
        this.context = Arrays.clone(context);
    }

    public void copyContextTo(byte[] buf, int off, int len)
    {
        if (context.length != len)
        {
            throw new IllegalArgumentException("len");
        }

        System.arraycopy(context, 0, buf, off, len);
    }

    public byte[] getContext()
    {
        return Arrays.clone(context);
    }

    public int getContextLength()
    {
        return context.length;
    }

    public CipherParameters getParameters()
    {
        return parameters;
    }
}
