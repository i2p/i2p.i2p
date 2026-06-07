package org.bouncycastle.crypto;

import org.bouncycastle.crypto.digests.EncodableDigest;
import org.bouncycastle.util.Memoable;

/**
 * Extended digest which provides the ability to store state and
 * provide an encoding.
 */
public interface SavableDigest
    extends ExtendedDigest, EncodableDigest, Memoable
{
}
