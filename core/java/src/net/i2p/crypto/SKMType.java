package net.i2p.crypto;

/**
 * Enums for the various SessionKeyManagers, returned by getSKMType(),
 * to avoid instanceof()
 *
 * @since 0.9.71
 */
public enum SKMType {

    /**
     *  SessionKeyManager, app context
     */
    DUMMY,
    /**
     *  TransientSessionKeyManager
     */
    ELGAMAL,
    /**
     *  Single type, RATCHET or PQ-RATCHET
     */
    RATCHET,
    /**
     *  ELGAMAL and RATCHET
     */
    MUXED,
    /**
     *  RATCHET and PQ-RATCHET
     */
    MUXEDPQ
}
