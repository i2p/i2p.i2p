package org.bouncycastle.crypto;

public interface CryptoServiceProperties
{
    int bitsOfSecurity();

    String getServiceName();

    CryptoServicePurpose getPurpose();

    Object getParams();
}
