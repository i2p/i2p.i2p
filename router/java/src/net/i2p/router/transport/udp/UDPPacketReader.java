package net.i2p.router.transport.udp;

import java.net.InetAddress;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.SessionKey;
import net.i2p.data.Signature;
import net.i2p.util.Log;

/**
 * To read a packet, initialize this reader with the data and fetch out
 * the appropriate fields.  If the interesting bits are in message specific
 * elements, grab the appropriate subreader.
 *
 */
class UDPPacketReader {
    private final I2PAppContext _context;
    private final Log _log;
    private byte _message[];
    private int _payloadBeginOffset;
    private int _payloadLength;
    private final SessionRequestReader _sessionRequestReader;
    private final SessionCreatedReader _sessionCreatedReader;
    private final SessionConfirmedReader _sessionConfirmedReader;
    private final DataReader _dataReader;
    private final PeerTestReader _peerTestReader;
    private final RelayRequestReader _relayRequestReader;
    private final RelayIntroReader _relayIntroReader;
    private final RelayResponseReader _relayResponseReader;
    
    private static final int KEYING_MATERIAL_LENGTH = 64;
    
    public UDPPacketReader(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(UDPPacketReader.class);
        _sessionRequestReader = new SessionRequestReader();
        _sessionCreatedReader = new SessionCreatedReader();
        _sessionConfirmedReader = new SessionConfirmedReader();
        _dataReader = new DataReader();
        _peerTestReader = new PeerTestReader();
        _relayRequestReader = new RelayRequestReader();
        _relayIntroReader = new RelayIntroReader();
        _relayResponseReader = new RelayResponseReader();
    }
    
    public void initialize(UDPPacket packet) {
        int off = packet.getPacket().getOffset();
        int len = packet.getPacket().getLength();
        off += UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        len -= UDPPacket.MAC_SIZE + UDPPacket.IV_SIZE;
        initialize(packet.getPacket().getData(), off, len);
    }
    
    public void initialize(byte message[], int payloadOffset, int payloadLength) {
        _message = message;
        _payloadBeginOffset = payloadOffset;
        _payloadLength = payloadLength;
    }
    
    /** what type of payload is in here? */
    public int readPayloadType() {
        // 4 highest order bits == payload type
        return (_message[_payloadBeginOffset] & 0xFF) >>> 4;
    }
    
    /** does this packet include rekeying data? */
    public boolean readRekeying() {
        return (_message[_payloadBeginOffset] & (1 << 3)) != 0;
    }
    
    public boolean readExtendedOptionsIncluded() {
        return (_message[_payloadBeginOffset] & (1 << 2)) != 0;
    }
    
    /** @return seconds */
    public long readTimestamp() {
        return DataHelper.fromLong(_message, _payloadBeginOffset + 1, 4);
    }
    
    public void readKeyingMaterial(byte target[], int targetOffset) {
        if (!readRekeying())
            throw new IllegalStateException("This packet is not rekeying!");
        System.arraycopy(_message, _payloadBeginOffset + 1 + 4, target, targetOffset, KEYING_MATERIAL_LENGTH);
    }
    
    /** index into the message where the body begins */
    private int readBodyOffset() {
        int offset = _payloadBeginOffset + 1 + 4;
        if (readRekeying())
            offset += KEYING_MATERIAL_LENGTH;
        if (readExtendedOptionsIncluded()) {
            int optionsSize = (int)DataHelper.fromLong(_message, offset, 1);
            offset += optionsSize + 1;
        }
        return offset;
    }
    
    public SessionRequestReader getSessionRequestReader() { return _sessionRequestReader; }
    public SessionCreatedReader getSessionCreatedReader() { return _sessionCreatedReader; }
    public SessionConfirmedReader getSessionConfirmedReader() { return _sessionConfirmedReader; }
    public DataReader getDataReader() { return _dataReader; }
    public PeerTestReader getPeerTestReader() { return _peerTestReader; }
    public RelayRequestReader getRelayRequestReader() { return _relayRequestReader; }
    public RelayIntroReader getRelayIntroReader() { return _relayIntroReader; }
    public RelayResponseReader getRelayResponseReader() { return _relayResponseReader; }
    
    @Override
    public String toString() {
        switch (readPayloadType()) {
            case UDPPacket.PAYLOAD_TYPE_DATA:
                return _dataReader.toString();
            case UDPPacket.PAYLOAD_TYPE_SESSION_CONFIRMED:
                return "Session confirmed packet";
            case UDPPacket.PAYLOAD_TYPE_SESSION_CREATED:
                return "Session created packet";
            case UDPPacket.PAYLOAD_TYPE_SESSION_REQUEST:
                return "Session request packet";
            case UDPPacket.PAYLOAD_TYPE_TEST:
                return "Peer test packet";
            case UDPPacket.PAYLOAD_TYPE_RELAY_INTRO:
                return "Relay intro packet";
            case UDPPacket.PAYLOAD_TYPE_RELAY_REQUEST:
                return "Relay request packet";
            case UDPPacket.PAYLOAD_TYPE_RELAY_RESPONSE:
                return "Relay response packet";
            case UDPPacket.PAYLOAD_TYPE_SESSION_DESTROY:
                return "Session destroyed packet";
            default:
                return "Other packet type...";
        }
    }
    
    public void toRawString(StringBuilder buf) {
        if (_message != null)
            buf.append(Base64.encode(_message, _payloadBeginOffset, _payloadLength));
    }
    
    /* ------- Begin Reader Classes ------- */

    /** Help read the SessionRequest payload */
    public class SessionRequestReader {
        public static final int X_LENGTH = 256;
        public void readX(byte target[], int targetOffset) {
            int readOffset = readBodyOffset();
            System.arraycopy(_message, readOffset, target, targetOffset, X_LENGTH);
        }
        
        public int readIPSize() {
            int offset = readBodyOffset() + X_LENGTH;
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        
        /** what IP bob is reachable on */
        public void readIP(byte target[], int targetOffset) {
            int offset = readBodyOffset() + X_LENGTH;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
        }
    }
    
    /** Help read the SessionCreated payload */
    public class SessionCreatedReader {
        public static final int Y_LENGTH = 256;
        public void readY(byte target[], int targetOffset) {
            int readOffset = readBodyOffset();
            System.arraycopy(_message, readOffset, target, targetOffset, Y_LENGTH);
        }
        
        /** sizeof(IP) */
        public int readIPSize() {
            int offset = readBodyOffset() + Y_LENGTH;
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        
        /** what IP do they think we are coming on? */
        public void readIP(byte target[], int targetOffset) {
            int offset = readBodyOffset() + Y_LENGTH;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
        }
        
        /** what port do they think we are coming from? */
        public int readPort() {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize();
            return (int)DataHelper.fromLong(_message, offset, 2);
        }
        
        /** read in the 4 byte relayAs tag */
        public long readRelayTag() {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize() + 2;
            return DataHelper.fromLong(_message, offset, 4);
        }
        
        public long readSignedOnTime() {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize() + 2 + 4;
            long rv = DataHelper.fromLong(_message, offset, 4);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("Signed on time offset: " + offset + " val: " + rv
                           + "\nRawCreated: " + Base64.encode(_message, _payloadBeginOffset, _payloadLength));
            return rv;
        }
        
        public void readEncryptedSignature(byte target[], int targetOffset) {
            int offset = readBodyOffset() + Y_LENGTH + 1 + readIPSize() + 2 + 4 + 4;
            System.arraycopy(_message, offset, target, targetOffset, Signature.SIGNATURE_BYTES + 8);
        }
        
        public void readIV(byte target[], int targetOffset) {
            int offset = _payloadBeginOffset - UDPPacket.IV_SIZE;
            System.arraycopy(_message, offset, target, targetOffset, UDPPacket.IV_SIZE);
        }
    }
    
    /** parse out the confirmed message */
    public class SessionConfirmedReader {
        /** which fragment is this? */
        public int readCurrentFragmentNum() {
            int readOffset = readBodyOffset();
            return (_message[readOffset] & 0xFF) >>> 4;
        }
        /** how many fragments will there be? */
        public int readTotalFragmentNum() {
            int readOffset = readBodyOffset();
            return (_message[readOffset] & 0xF);
        }
        
        public int readCurrentFragmentSize() {
            int readOffset = readBodyOffset() + 1;
            return (int)DataHelper.fromLong(_message, readOffset, 2);
        }

        /** read the fragment data from the nonterminal sessionConfirmed packet */
        public void readFragmentData(byte target[], int targetOffset) {
            int readOffset = readBodyOffset() + 1 + 2;
            int len = readCurrentFragmentSize();
            System.arraycopy(_message, readOffset, target, targetOffset, len);
        }
        
        /** read the time at which the signature was generated */
        public long readFinalFragmentSignedOnTime() {
            if (readCurrentFragmentNum() != readTotalFragmentNum()-1)
                throw new IllegalStateException("This is not the final fragment");
            int readOffset = readBodyOffset() + 1 + 2 + readCurrentFragmentSize();
            return DataHelper.fromLong(_message, readOffset, 4);
        }
        
        /** read the signature from the final sessionConfirmed packet */
        public void readFinalSignature(byte target[], int targetOffset) {
            if (readCurrentFragmentNum() != readTotalFragmentNum()-1)
                throw new IllegalStateException("This is not the final fragment");
            int readOffset = _payloadBeginOffset + _payloadLength - Signature.SIGNATURE_BYTES;
            System.arraycopy(_message, readOffset, target, targetOffset, Signature.SIGNATURE_BYTES);
        }
    }
    
    /** parse out the data message */
    public class DataReader {
        public int getPacketSize() { return _payloadLength; }
        public boolean readACKsIncluded() {
            return flagSet(UDPPacket.DATA_FLAG_EXPLICIT_ACK);
        }
        public boolean readACKBitfieldsIncluded() {
            return flagSet(UDPPacket.DATA_FLAG_ACK_BITFIELDS);
        }
        public boolean readECN() {
            return flagSet(UDPPacket.DATA_FLAG_ECN);
        }
        public boolean readWantPreviousACKs() {
            return flagSet(UDPPacket.DATA_FLAG_WANT_ACKS);
        }
        public boolean readReplyRequested() { 
            return flagSet(UDPPacket.DATA_FLAG_WANT_REPLY);
        }
        public boolean readExtendedDataIncluded() {
            return flagSet(UDPPacket.DATA_FLAG_EXTENDED);
        }
        public int readACKCount() {
            if (!readACKsIncluded()) return 0;
            int off = readBodyOffset() + 1;
            return (int)DataHelper.fromLong(_message, off, 1);
        }
        public long readACK(int index) {
            if (!readACKsIncluded()) return -1;
            int off = readBodyOffset() + 1;
            //int num = (int)DataHelper.fromLong(_message, off, 1);
            off++;
            return DataHelper.fromLong(_message, off + (4 * index), 4);
        }
        public ACKBitfield[] readACKBitfields() {
            if (!readACKBitfieldsIncluded()) return null;
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numACKs;
            }
            
            int numBitfields = (int)DataHelper.fromLong(_message, off, 1);
            off++;
            
            PacketACKBitfield rv[] = new PacketACKBitfield[numBitfields];
            for (int i = 0; i < numBitfields; i++) {
                rv[i] = new PacketACKBitfield(off);
                off += rv[i].getByteLength();
            }
            return rv;
        }
        
        public int readFragmentCount() {
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numACKs;
            }
            if (readACKBitfieldsIncluded()) {
                int numBitfields = (int)DataHelper.fromLong(_message, off, 1);
                off++;

                for (int i = 0; i < numBitfields; i++) {
                    PacketACKBitfield bf = new PacketACKBitfield(off);
                    off += bf.getByteLength();
                }
            }
            if (readExtendedDataIncluded()) {
                int size = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += size;
            }
            return (int)_message[off];
        }
        
        public long readMessageId(int fragmentNum) {
            int fragmentBegin = getFragmentBegin(fragmentNum);
            return DataHelper.fromLong(_message, fragmentBegin, 4);
        }
        public int readMessageFragmentNum(int fragmentNum) {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            return (_message[off] & 0xFF) >>> 1;
        }
        public boolean readMessageIsLast(int fragmentNum) {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            return ((_message[off] & 1) != 0);
        }
        public int readMessageFragmentSize(int fragmentNum) {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            off++; // fragment info
            return ((int)DataHelper.fromLong(_message, off, 2)) & 0x3FFF;
        }

        public void readMessageFragment(int fragmentNum, byte target[], int targetOffset)
                                                      throws ArrayIndexOutOfBoundsException {
            int off = getFragmentBegin(fragmentNum);
            off += 4; // messageId
            off++; // fragment info
            int size = ((int)DataHelper.fromLong(_message, off, 2)) & 0x3FFF;
            off += 2;
            System.arraycopy(_message, off, target, targetOffset, size);
        }
        
        private int getFragmentBegin(int fragmentNum) {
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += 4 * numACKs;
            }
            if (readACKBitfieldsIncluded()) {
                int numBitfields = (int)DataHelper.fromLong(_message, off, 1);
                off++;

                PacketACKBitfield bf[] = new PacketACKBitfield[numBitfields];
                for (int i = 0; i < numBitfields; i++) {
                    bf[i] = new PacketACKBitfield(off);
                    off += bf[i].getByteLength();
                }
            }
            if (readExtendedDataIncluded()) {
                int size = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                off += size;
            }
            off++; // # fragments
            
            if (fragmentNum == 0) {
                return off;
            } else {
                for (int i = 0; i < fragmentNum; i++) {
                    off += 5; // messageId+info
                    off += ((int)DataHelper.fromLong(_message, off, 2)) & 0x3FFF;
                    off += 2;
                }
                return off;
            }
        }

        private boolean flagSet(byte flag) {
            int flagOffset = readBodyOffset();
            return ((_message[flagOffset] & flag) != 0);
        }
        
        @Override
        public String toString() {
            StringBuilder buf = new StringBuilder(512);
            long msAgo = _context.clock().now() - readTimestamp()*1000;
            buf.append("Data packet sent ").append(msAgo).append("ms ago ");
            buf.append("IV ");
            buf.append(Base64.encode(_message, _payloadBeginOffset-UDPPacket.IV_SIZE, UDPPacket.IV_SIZE));
            buf.append(" ");
            int off = readBodyOffset() + 1;
            if (readACKsIncluded()) {
                int numACKs = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                buf.append("with ACKs for ");
                for (int i = 0; i < numACKs; i++) {
                    buf.append(DataHelper.fromLong(_message, off, 4)).append(' ');
                    off += 4;
                }
            }
            if (readACKBitfieldsIncluded()) {
                int numBitfields = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                buf.append("with partial ACKs for ");

                for (int i = 0; i < numBitfields; i++) {
                    PacketACKBitfield bf = new PacketACKBitfield(off);
                    buf.append(bf.getMessageId()).append(' ');
                    off += bf.getByteLength();
                }
            }
            if (readExtendedDataIncluded()) {
                int size = (int)DataHelper.fromLong(_message, off, 1);
                off++;
                buf.append("with extended size of ");
                buf.append(size);
                buf.append(' ');
                off += size;
            }
            
            int numFragments = (int)DataHelper.fromLong(_message, off, 1);
            off++;
            buf.append("with fragmentCount of ");
            buf.append(numFragments);
            buf.append(' ');
            
            for (int i = 0; i < numFragments; i++) {
                buf.append("containing messageId ");
                buf.append(DataHelper.fromLong(_message, off, 4));
                off += 4;
                int fragNum = (_message[off] & 0xFF) >>> 1;
                boolean isLast = (_message[off] & 1) != 0;
                off++;
                buf.append(" frag# ").append(fragNum);
                buf.append(" isLast? ").append(isLast);
                buf.append(" info ").append((int)_message[off-1]);
                int size = ((int)DataHelper.fromLong(_message, off, 2)) & 0x3FFF;
                buf.append(" with ").append(size).append(" bytes");
                buf.append(' ');
                off += size;
                off += 2;
            }
            
            return buf.toString();
        }
        
        public void toRawString(StringBuilder buf) { 
            UDPPacketReader.this.toRawString(buf); 
            buf.append(" payload: ");
                  
            int off = getFragmentBegin(0); // first fragment
            off += 4; // messageId
            off++; // fragment info
            int size = ((int)DataHelper.fromLong(_message, off, 2)) & 0x3FFF;
            off += 2;
            buf.append(Base64.encode(_message, off, size));
        }
    }
    
    /**
     * Helper class to fetch the particular bitfields from the raw packet
     */   
    private class PacketACKBitfield implements ACKBitfield {
        private int _start;
        private int _bitfieldStart;
        private int _bitfieldSize;
        public PacketACKBitfield(int start) {
            _start = start;
            _bitfieldStart = start + 4;
            _bitfieldSize = 1;
            // bitfield is an array of bytes where the high bit is 1 if 
            // further bytes in the bitfield follow
            while ((_message[_bitfieldStart + _bitfieldSize - 1] & UDPPacket.BITFIELD_CONTINUATION) != 0x0)
                _bitfieldSize++;
        }
        public long getMessageId() { return DataHelper.fromLong(_message, _start, 4); }
        public int getByteLength() { return 4 + _bitfieldSize; }
        public int fragmentCount() { return _bitfieldSize * 7; }
        public boolean receivedComplete() { return false; }
        public boolean received(int fragmentNum) {
            if ( (fragmentNum < 0) || (fragmentNum >= _bitfieldSize*7) )
                return false;
            // the fragment has been received if the bit is set
            int byteNum = _bitfieldStart + (fragmentNum/7);
            int flagNum = fragmentNum % 7;
            return (_message[byteNum] & (1 << flagNum)) != 0x0;
        }
        @Override
        public String toString() { 
            StringBuilder buf = new StringBuilder(64);
            buf.append("Read partial ACK of ");
            buf.append(getMessageId());
            buf.append(" with ACKs for: ");
            int numFrags = fragmentCount();
            for (int i = 0; i < numFrags; i++) {
                if (received(i))
                    buf.append(i).append(" ");
                else
                    buf.append('!').append(i).append(" ");
            }
            return buf.toString();
        }
    }
    
    /** Help read the PeerTest payload */
    public class PeerTestReader {
        private static final int NONCE_LENGTH = 4;
        
        public long readNonce() {
            int readOffset = readBodyOffset();
            return DataHelper.fromLong(_message, readOffset, NONCE_LENGTH);
        }
        
        public int readIPSize() {
            int offset = readBodyOffset() + NONCE_LENGTH;
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        
        /** what IP Alice is reachable on */
        public void readIP(byte target[], int targetOffset) {
            int offset = readBodyOffset() + NONCE_LENGTH;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
        }
        
        /** what IP Alice is reachable on */
        public int readPort() {
            int offset = readBodyOffset() + NONCE_LENGTH;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += size; // skip the IP
            return (int)DataHelper.fromLong(_message, offset, 2);
        }
        
        /** what Alice's intro key is (if known - if unknown, the key is INVALID_KEY) */
        public void readIntroKey(byte target[], int targetOffset) {
            int offset = readBodyOffset() + NONCE_LENGTH;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += size; // skip the IP
            offset += 2; // skip the port
            System.arraycopy(_message, offset, target, targetOffset, SessionKey.KEYSIZE_BYTES);
        }
    }
    
    /** Help read the RelayRequest payload */
    public class RelayRequestReader {
        public long readTag() { 
            long rv = DataHelper.fromLong(_message, readBodyOffset(), 4); 
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read alice tag: " + rv);
            return rv;
        }
        public int readIPSize() {
            int offset = readBodyOffset() + 4;
            int rv = (int)DataHelper.fromLong(_message, offset, 1);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read alice ip size: " + rv);
            return rv;
        }
        
        /** what IP Alice is reachable on */
        public void readIP(byte target[], int targetOffset) {
            int offset = readBodyOffset() + 4;
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read alice ip: " + Base64.encode(target, targetOffset, size));
        }
        public int readPort() {
            int offset = readBodyOffset() + 4;
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            int rv = (int)DataHelper.fromLong(_message, offset, 2);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read alice port: " + rv);
            return rv;
        }

        /** unused */
        public int readChallengeSize() {
            int offset = readBodyOffset() + 4;
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int rv = (int)DataHelper.fromLong(_message, offset, 1);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read challenge size: " + rv);
            return rv;
        }

        /** unused */
        public void readChallengeSize(byte target[], int targetOffset) {
            int offset = readBodyOffset() + 4;
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int sz = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, sz);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read challenge data: " + Base64.encode(target));
        }
        public void readAliceIntroKey(byte target[], int targetOffset) {
            int offset = readBodyOffset() + 4;
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int sz = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += sz;
            System.arraycopy(_message, offset, target, targetOffset, SessionKey.KEYSIZE_BYTES);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read alice intro key: " + Base64.encode(target, targetOffset, SessionKey.KEYSIZE_BYTES)
                          + " packet size: " + _payloadLength + " off: " + offset + " data: " + Base64.encode(_message));
        }
        public long readNonce() {
            int offset = readBodyOffset() + 4;
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int sz = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += sz;
            offset += SessionKey.KEYSIZE_BYTES;
            long rv = DataHelper.fromLong(_message, offset, 4);
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("read request nonce: " + rv);
            return rv;
        }
    }
    
    /** Help read the RelayIntro payload */
    public class RelayIntroReader {
        public int readIPSize() {
            int offset = readBodyOffset();
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        
        /** what IP Alice is reachable on */
        public void readIP(byte target[], int targetOffset) {
            int offset = readBodyOffset();
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
        }
        public int readPort() {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            return (int)DataHelper.fromLong(_message, offset, 2);
        }

        /** unused */
        public int readChallengeSize() {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            return (int)DataHelper.fromLong(_message, offset, 1);
        }

        /** unused */
        public void readChallengeSize(byte target[], int targetOffset) {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int sz = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, sz);
        }
    }
    
    
    /** Help read the RelayResponse payload */
    public class RelayResponseReader {
        public int readCharlieIPSize() {
            int offset = readBodyOffset();
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        /** what IP charlie is reachable on */
        public void readCharlieIP(byte target[], int targetOffset) {
            int offset = readBodyOffset();
            int size = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, size);
        }
        /** what port charlie is reachable on */
        public int readCharliePort() {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            return (int)DataHelper.fromLong(_message, offset, 2);
        }
        
        public int readAliceIPSize() {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            return (int)DataHelper.fromLong(_message, offset, 1);
        }
        public void readAliceIP(byte target[], int targetOffset) {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int sz = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            System.arraycopy(_message, offset, target, targetOffset, sz);
        }
        public int readAlicePort() {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int sz = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += sz;
            return (int)DataHelper.fromLong(_message, offset, 2);
        }
        public long readNonce() {
            int offset = readBodyOffset();
            offset += DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += 2;
            int sz = (int)DataHelper.fromLong(_message, offset, 1);
            offset++;
            offset += sz;
            offset += 2;
            return DataHelper.fromLong(_message, offset, 4);
        }
    }
    
    /* ------- End Reader Classes ------- */
    
/******
    public static void main(String args[]) {
        I2PAppContext ctx = I2PAppContext.getGlobalContext();
        try {
            PacketBuilder b = new PacketBuilder(ctx, null);
            InetAddress introHost = InetAddress.getLocalHost();
            int introPort = 1234;
            byte introKey[] = new byte[SessionKey.KEYSIZE_BYTES];
            ctx.random().nextBytes(introKey);
            long introTag = ctx.random().nextLong(0xFFFFFFFFl);
            long introNonce = ctx.random().nextLong(0xFFFFFFFFl);
            SessionKey ourIntroKey = ctx.keyGenerator().generateSessionKey();
            UDPPacket packet = b.buildRelayRequest(introHost, introPort, introKey, introTag, ourIntroKey, introNonce, false);
            UDPPacketReader r = new UDPPacketReader(ctx);
            r.initialize(packet);
            RelayRequestReader reader = r.getRelayRequestReader();
            System.out.println("Nonce: " + reader.readNonce() + " / " + introNonce);
            System.out.println("Tag  : " + reader.readTag() + " / " + introTag);
            byte readKey[] = new byte[SessionKey.KEYSIZE_BYTES];
            reader.readAliceIntroKey(readKey, 0);
            System.out.println("Key  : " + Base64.encode(readKey) + " / " + ourIntroKey.toBase64());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }
*******/
}
