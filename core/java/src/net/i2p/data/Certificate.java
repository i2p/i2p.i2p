package net.i2p.data;

/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Defines a certificate that can be attached to various I2P structures, such
 * as RouterIdentity and Destination, allowing routers and clients to help
 * manage denial of service attacks and the network utilization.  Certificates
 * can even be defined to include identifiable information signed by some 
 * certificate authority, though that use probably isn't appropriate for an
 * anonymous network ;)
 *
 * Todo: Properly support multiple certificates
 *
 * @author jrandom
 */
public class Certificate extends DataStructureImpl {
    public final static Certificate NULL_CERT = new NullCert();

    protected int _type;
    protected byte[] _payload;

    /** Specifies a null certificate type with no payload */
    public final static int CERTIFICATE_TYPE_NULL = 0;
    /** specifies a Hashcash style certificate */
    public final static int CERTIFICATE_TYPE_HASHCASH = 1;
    /** we should not be used for anything (don't use us in the netDb, in tunnels, or tell others about us) */
    public final static int CERTIFICATE_TYPE_HIDDEN = 2;
    /** Signed with 40-byte Signature and (optional) 32-byte hash */
    public final static int CERTIFICATE_TYPE_SIGNED = 3;
    public final static int CERTIFICATE_LENGTH_SIGNED_WITH_HASH = Signature.SIGNATURE_BYTES + Hash.HASH_LENGTH;
    /** Contains multiple certs */
    public final static int CERTIFICATE_TYPE_MULTIPLE = 4;

    /**
     * Pull from cache or return new
     * @throws AIOOBE if not enough bytes
     * @since 0.8.3
     */
    public static Certificate create(byte[] data, int off) {
        int type = data[off] & 0xff;
        int length = (int) DataHelper.fromLong(data, off + 1, 2);
        if (type == 0 && length == 0)
            return NULL_CERT;
        // from here down roughly the same as readBytes() below
        if (length == 0)
            return new Certificate(type, null);
        byte[] payload = new byte[length];
        System.arraycopy(data, off = 3, payload, 0, length);
        return new Certificate(type, payload);
    }

    /**
     * If null cert, return immutable static instance, else create new
     * @since 0.8.3
     */
    public static Certificate create(InputStream in) throws DataFormatException, IOException {
        int type = (int) DataHelper.readLong(in, 1);
        int length = (int) DataHelper.readLong(in, 2);
        if (type == 0 && length == 0)
            return NULL_CERT;
        // from here down roughly the same as readBytes() below
        if (length == 0)
            return new Certificate(type, null);
        byte[] payload = new byte[length];
        int read = DataHelper.read(in, payload);
        if (read != length)
            throw new DataFormatException("Not enough bytes for the payload (read: " + read + " length: " + length + ')');
        return new Certificate(type, payload);
    }

    public Certificate() {
    }

    public Certificate(int type, byte[] payload) {
        _type = type;
        _payload = payload;
    }

    /** */
    public int getCertificateType() {
        return _type;
    }

    public void setCertificateType(int type) {
        _type = type;
    }

    public byte[] getPayload() {
        return _payload;
    }

    public void setPayload(byte[] payload) {
        _payload = payload;
    }
    
    public void readBytes(InputStream in) throws DataFormatException, IOException {
        _type = (int) DataHelper.readLong(in, 1);
        int length = (int) DataHelper.readLong(in, 2);
        if (length > 0) {
            _payload = new byte[length];
            int read = read(in, _payload);
            if (read != length)
                throw new DataFormatException("Not enough bytes for the payload (read: " + read + " length: " + length
                                              + ")");
        }
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException, IOException {
        if (_type < 0) throw new DataFormatException("Invalid certificate type: " + _type);
        //if ((_type != 0) && (_payload == null)) throw new DataFormatException("Payload is required for non null type");

        DataHelper.writeLong(out, 1, _type);
        if (_payload != null) {
            DataHelper.writeLong(out, 2, _payload.length);
            out.write(_payload);
        } else {
            DataHelper.writeLong(out, 2, 0L);
        }
    }

    /**
     *  @return the written length (NOT the new offset)    
     */    
    public int writeBytes(byte target[], int offset) {
        int cur = offset;
        DataHelper.toLong(target, cur, 1, _type);
        cur++;
        if (_payload != null) {
            DataHelper.toLong(target, cur, 2, _payload.length);
            cur += 2;
            System.arraycopy(_payload, 0, target, cur, _payload.length);
            cur += _payload.length;
        } else {
            DataHelper.toLong(target, cur, 2, 0);
            cur += 2;
        }
        return cur - offset;
    }
    
    public int readBytes(byte source[], int offset) throws DataFormatException {
        if (source == null) throw new DataFormatException("Cert is null");
        if (source.length < offset + 3)
            throw new DataFormatException("Cert is too small [" + source.length + " off=" + offset + "]");

        int cur = offset;
        _type = (int)DataHelper.fromLong(source, cur, 1);
        cur++;
        int length = (int)DataHelper.fromLong(source, cur, 2);
        cur += 2;
        if (length > 0) {
            if (length + cur > source.length)
                throw new DataFormatException("Payload on the certificate is insufficient (len=" 
                                              + source.length + " off=" + offset + " cur=" + cur
                                              + " payloadLen=" + length);
            _payload = new byte[length];
            System.arraycopy(source, cur, _payload, 0, length);
            cur += length;
        }
        return cur - offset;
    }
    
    public int size() {
        return 1 + 2 + (_payload != null ? _payload.length : 0);
    }
    
    @Override
    public boolean equals(Object object) {
        if ((object == null) || !(object instanceof Certificate)) return false;
        Certificate cert = (Certificate) object;
        return _type == cert.getCertificateType() && DataHelper.eq(_payload, cert.getPayload());
    }

    @Override
    public int hashCode() {
        return _type + DataHelper.hashCode(_payload);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[Certificate: type: ");
        if (getCertificateType() == CERTIFICATE_TYPE_NULL)
            buf.append("Null certificate");
        else if (getCertificateType() == CERTIFICATE_TYPE_HASHCASH)
            buf.append("Hashcash certificate");
        else if (getCertificateType() == CERTIFICATE_TYPE_HIDDEN)
            buf.append("Hidden certificate");
        else if (getCertificateType() == CERTIFICATE_TYPE_SIGNED)
            buf.append("Signed certificate");
        else
            buf.append("Unknown certificate type (").append(getCertificateType()).append(")");

        if (_payload == null) {
            buf.append(" null payload");
        } else {
            buf.append(" payload size: ").append(_payload.length);
            if (getCertificateType() == CERTIFICATE_TYPE_HASHCASH) {
                buf.append(" Stamp: ").append(new String(_payload));
            } else if (getCertificateType() == CERTIFICATE_TYPE_SIGNED && _payload.length == CERTIFICATE_LENGTH_SIGNED_WITH_HASH) {
                buf.append(" Signed by hash: ").append(Base64.encode(_payload, Signature.SIGNATURE_BYTES, Hash.HASH_LENGTH));
            } else {
                int len = 32;
                if (len > _payload.length) len = _payload.length;
                buf.append(" first ").append(len).append(" bytes: ");
                buf.append(DataHelper.toString(_payload, len));
            }
        }
        buf.append("]");
        return buf.toString();
    }

    /**
     *  An immutable null certificate.
     *  @since 0.8.3
     */
    private static final class NullCert extends Certificate {
        private static final int NULL_LENGTH = 1 + 2;
        private static final byte[] NULL_DATA = new byte[NULL_LENGTH];

        public NullCert() {
            // zero already
            //_type = CERTIFICATE_TYPE_NULL;
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
            out.write(NULL_DATA);
        }
    
        /** Overridden for efficiency */
        @Override
        public int writeBytes(byte target[], int offset) {
            System.arraycopy(NULL_DATA, 0, target, offset, NULL_LENGTH);
            return NULL_LENGTH;
        }
    
        /** @throws RuntimeException always */
        @Override
        public int readBytes(byte source[], int offset) throws DataFormatException {
            throw new RuntimeException("Data already set");
        }
    
        /** Overridden for efficiency */
        @Override
        public int size() {
            return NULL_LENGTH;
        }
    
        /** Overridden for efficiency */
        @Override
        public int hashCode() {
            return 99999;
        }
    }
}
