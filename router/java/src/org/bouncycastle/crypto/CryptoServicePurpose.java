package org.bouncycastle.crypto;

public enum CryptoServicePurpose
{
    AGREEMENT,
    ENCRYPTION,
    DECRYPTION,
    KEYGEN,
    SIGNING,         // for signatures (and digests)
    VERIFYING,
    AUTHENTICATION,  // for MACs (and digests)
    VERIFICATION,
    PRF,
    ANY
}
