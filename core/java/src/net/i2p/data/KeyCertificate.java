package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.crypto.SigType;

/**
 * This certificate type gets its own class because it's going to be used a lot.
 *
 * Format is: 2 byte sig type, 2 byte crypto type, excess sig data, excess crypto data.
 *
 * The crypto type is assumed to be always 0x0000 (ElG) for now.
 *
 * @since 0.9.12
 */
public class KeyCertificate extends Certificate {

    public static final int HEADER_LENGTH = 4;

    /** @since 0.9.22 pkg private for Certificate.create() */
    static final byte[] Ed25519_PAYLOAD = new byte[] {
        0, (byte) (SigType.EdDSA_SHA512_Ed25519.getCode()), 0, 0
    };

    /** @since 0.9.22 pkg private for Certificate.create() */
    static final byte[] ECDSA256_PAYLOAD = new byte[] {
        0, (byte) (SigType.ECDSA_SHA256_P256.getCode()), 0, 0
    };

    /**
     *  An immutable ElG/ECDSA-P256 certificate.
     */
    public static final KeyCertificate ELG_ECDSA256_CERT;

    /**
     *  An immutable ElG/Ed25519 certificate.
     *  @since 0.9.22
     */
    public static final KeyCertificate ELG_Ed25519_CERT;

    static {
        KeyCertificate kc;
        try {
            kc = new ECDSA256Cert();
        } catch (DataFormatException dfe) {
            throw new RuntimeException(dfe);  // won't happen
        }
        ELG_ECDSA256_CERT = kc;
        try {
            kc = new Ed25519Cert();
        } catch (DataFormatException dfe) {
            throw new RuntimeException(dfe);  // won't happen
        }
        ELG_Ed25519_CERT = kc;
    }

    /**
     *  @param payload 4 bytes minimum if non-null
     *  @throws DataFormatException
     */
    public KeyCertificate(byte[] payload) throws DataFormatException {
         super(CERTIFICATE_TYPE_KEY, payload);
         if (payload != null && payload.length < HEADER_LENGTH)
             throw new DataFormatException("data");
    }

    /**
     *  A KeyCertificate with crypto type 0 (ElGamal)
     *  and the signature type and extra data from the given public key.
     *
     *  @param spk non-null data non-null
     *  @throws IllegalArgumentException
     */
    public KeyCertificate(SigningPublicKey spk) {
         super(CERTIFICATE_TYPE_KEY, null);
         if (spk == null || spk.getData() == null)
             throw new IllegalArgumentException();
         SigType type = spk.getType();
         int len = type.getPubkeyLen();
         int extra = Math.max(0, len - 128);
         _payload = new byte[HEADER_LENGTH + extra];
         int code = type.getCode();
         _payload[0] = (byte) (code >> 8);
         _payload[1] = (byte) (code & 0xff);
         // 2 and 3 always 0, it is the only crypto code for now
         if (extra > 0)
             System.arraycopy(spk.getData(), 128, _payload, HEADER_LENGTH, extra);
    }

    /**
     *  A KeyCertificate with crypto type 0 (ElGamal)
     *  and the signature type as specified.
     *  Payload is created.
     *  If type.getPubkeyLen() is greater than 128, caller MUST
     *  fill in the extra key data in the payload.
     *
     *  @param type non-null
     *  @throws IllegalArgumentException
     */
    public KeyCertificate(SigType type) {
         super(CERTIFICATE_TYPE_KEY, null);
         int len = type.getPubkeyLen();
         int extra = Math.max(0, len - 128);
         _payload = new byte[HEADER_LENGTH + extra];
         int code = type.getCode();
         _payload[0] = (byte) (code >> 8);
         _payload[1] = (byte) (code & 0xff);
         // 2 and 3 always 0, it is the only crypto code for now
    }

    /**
     *  Up-convert a cert to this class
     *
     *  @param cert payload 4 bytes minimum if non-null
     *  @throws DataFormatException if cert type != CERTIFICATE_TYPE_KEY
     */
    public KeyCertificate(Certificate cert) throws DataFormatException {
        this(cert.getPayload());
        if (cert.getCertificateType() != CERTIFICATE_TYPE_KEY)
            throw new DataFormatException("type");
    }

    /**
     *  @return -1 if unset
     */
    public int getSigTypeCode() {
        if (_payload == null)
            return -1;
        return ((_payload[0] & 0xff) << 8) | (_payload[1] & 0xff);
    }

    /**
     *  @return -1 if unset
     */
    public int getCryptoTypeCode() {
        if (_payload == null)
            return -1;
        return ((_payload[2] & 0xff) << 8) | (_payload[3] & 0xff);
    }

    /**
     *  @return null if unset or unknown
     */
    public SigType getSigType() {
        return SigType.getByCode(getSigTypeCode());
    }

    /**
     *  Signing Key extra data, if any, is first in the array.
     *  Crypto Key extra data, if any, is second in the array,
     *  at offset max(0, getSigType().getPubkeyLen() - 128)
     *
     *  @return null if unset or none
     */
    public byte[] getExtraKeyData() {
        if (_payload == null || _payload.length <= HEADER_LENGTH)
            return null;
        byte[] rv = new byte[_payload.length - HEADER_LENGTH];
        System.arraycopy(_payload, HEADER_LENGTH, rv, 0, rv.length);
        return rv;
    }


    /**
     *  Signing Key extra data, if any.
     *
     *  @return null if unset or none
     *  @throws UnsupportedOperationException if the sig type is unsupported
     */
    public byte[] getExtraSigningKeyData() {
        // we assume no crypto key data
        if (_payload == null || _payload.length <= HEADER_LENGTH)
            return null;
        SigType type = getSigType();
        if (type == null)
            throw new UnsupportedOperationException("unknown sig type");
        int extra = Math.max(0, type.getPubkeyLen() - 128);
        if (_payload.length == HEADER_LENGTH + extra)
            return getExtraKeyData();
        byte[] rv = new byte[extra];
        System.arraycopy(_payload, HEADER_LENGTH, rv, 0, extra);
        return rv;
    }

    // todo
    // constructor w/ crypto type
    // getCryptoType()
    // getCryptoDataOffset()

    @Override
    public KeyCertificate toKeyCertificate() {
        return this;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[Certificate: type: Key certificate");
        if (_payload == null) {
            buf.append(" null payload");
        } else {
            buf.append("\n\tCrypto type: ").append(getCryptoTypeCode());
            buf.append("\n\tSig type: ").append(getSigTypeCode())
               .append(" (").append(getSigType()).append(')');
            if (_payload.length > HEADER_LENGTH)
                buf.append("\n\tKey data: ").append(_payload.length - HEADER_LENGTH).append(" bytes");
        }
        buf.append("]");
        return buf.toString();
    }

    /**
     *  An immutable ElG/ECDSA-256 certificate.
     */
    private static final class ECDSA256Cert extends KeyCertificate {
        private static final byte[] ECDSA256_DATA = new byte[] {
            CERTIFICATE_TYPE_KEY, 0, HEADER_LENGTH, 0, (byte) (SigType.ECDSA_SHA256_P256.getCode()), 0, 0
        };
        private static final int ECDSA256_LENGTH = ECDSA256_DATA.length;
        private final int _hashcode;

        public ECDSA256Cert() throws DataFormatException {
            super(ECDSA256_PAYLOAD);
            _hashcode = super.hashCode();
        }

        /** @throws RuntimeException always */
        @Override
        public void setCertificateType(int type) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void setPayload(byte[] payload) {
            throw new RuntimeException("Data already set");
        }
    
        /** @throws RuntimeException always */
        @Override
        public void readBytes(InputStream in) throws DataFormatException, IOException {
            throw new RuntimeException("Data already set");
        }
    
        /** Overridden for efficiency */
        @Override
        public void writeBytes(OutputStream out) throws IOException {
            out.write(ECDSA256_DATA);
        }
    
        /** Overridden for efficiency */
        @Override
        public int writeBytes(byte target[], int offset) {
            System.arraycopy(ECDSA256_DATA, 0, target, offset, ECDSA256_LENGTH);
            return ECDSA256_LENGTH;
        }
    
        /** @throws RuntimeException always */
        @Override
        public int readBytes(byte source[], int offset) throws DataFormatException {
            throw new RuntimeException("Data already set");
        }
    
        /** Overridden for efficiency */
        @Override
        public int size() {
            return ECDSA256_LENGTH;
        }
    
        /** Overridden for efficiency */
        @Override
        public int hashCode() {
            return _hashcode;
        }
    }

    /**
     *  An immutable ElG/Ed25519 certificate.
     *  @since 0.9.22
     */
    private static final class Ed25519Cert extends KeyCertificate {
        private static final byte[] ED_DATA = new byte[] { CERTIFICATE_TYPE_KEY,
                                                           0, HEADER_LENGTH,
                                                           0, (byte) SigType.EdDSA_SHA512_Ed25519.getCode(),
                                                           0, 0
        };
        private static final int ED_LENGTH = ED_DATA.length;
        private final int _hashcode;

        public Ed25519Cert() throws DataFormatException {
            super(Ed25519_PAYLOAD);
            _hashcode = super.hashCode();
        }

        /** @throws RuntimeException always */
        @Override
        public void setCertificateType(int type) {
            throw new RuntimeException("Data already set");
        }

        /** @throws RuntimeException always */
        @Override
        public void setPayload(byte[] payload) {
            throw new RuntimeException("Data already set");
        }
    
        /** @throws RuntimeException always */
        @Override
        public void readBytes(InputStream in) throws DataFormatException, IOException {
            throw new RuntimeException("Data already set");
        }
    
        /** Overridden for efficiency */
        @Override
        public void writeBytes(OutputStream out) throws IOException {
            out.write(ED_DATA);
        }
    
        /** Overridden for efficiency */
        @Override
        public int writeBytes(byte target[], int offset) {
            System.arraycopy(ED_DATA, 0, target, offset, ED_LENGTH);
            return ED_LENGTH;
        }
    
        /** @throws RuntimeException always */
        @Override
        public int readBytes(byte source[], int offset) throws DataFormatException {
            throw new RuntimeException("Data already set");
        }
    
        /** Overridden for efficiency */
        @Override
        public int size() {
            return ED_LENGTH;
        }
    
        /** Overridden for efficiency */
        @Override
        public int hashCode() {
            return _hashcode;
        }
    }
}
