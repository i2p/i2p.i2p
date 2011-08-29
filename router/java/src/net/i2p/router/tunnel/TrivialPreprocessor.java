package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.router.RouterContext;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/** 
 * Do the simplest thing possible for preprocessing - for each message available,
 * turn it into the minimum number of fragmented preprocessed blocks, sending 
 * each of those out.  This does not coallesce message fragments or delay for more
 * optimal throughput.
 *
 * See FragmentHandler Javadoc for tunnel message fragment format
 */
public class TrivialPreprocessor implements TunnelGateway.QueuePreprocessor {
    protected final RouterContext _context;
    protected final Log _log;
    
    public static final int PREPROCESSED_SIZE = 1024;
    protected static final int IV_SIZE = HopProcessor.IV_LENGTH;

    /**
     * Here in tunnels, we take from the cache but never add to it.
     * In other words, we take advantage of other places in the router also using 1024-byte ByteCaches
     * (since ByteCache only maintains once instance for each size)
     * Used in BatchedPreprocessor; see add'l comments there
     */
    protected static final ByteCache _dataCache = ByteCache.getInstance(32, PREPROCESSED_SIZE);

    private static final ByteCache _ivCache = ByteCache.getInstance(128, IV_SIZE);
    private static final ByteCache _hashCache = ByteCache.getInstance(128, Hash.HASH_LENGTH);
    
    public TrivialPreprocessor(RouterContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(getClass());
    }
  
    /** how long do we want to wait before flushing */
    public long getDelayAmount() { return 0; }
  
    /**
     * Return true if there were messages remaining, and we should queue up
     * a delayed flush to clear them
     *
     * NOTE: Unused here, see BatchedPreprocessor override, super is not called.
     */
    public boolean preprocessQueue(List<TunnelGateway.Pending> pending, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        throw new IllegalArgumentException("unused, right?");
    }
    
    protected void notePreprocessing(long messageId, int numFragments, int totalLength, List<Long> messageIds, String msg) {}
    
    /**
     * Wrap the preprocessed fragments with the necessary padding / checksums 
     * to act as a tunnel message.
     *
     * @param fragmentLength fragments[0:fragmentLength] is used
     */
    protected void preprocess(byte fragments[], int fragmentLength) {
        ByteArray ivBuf = _ivCache.acquire();
        byte iv[] = ivBuf.getData(); // new byte[IV_SIZE];
        _context.random().nextBytes(iv);
        
        // payload ready, now H(instructions+payload+IV)
        System.arraycopy(iv, 0, fragments, fragmentLength, IV_SIZE);
        
        ByteArray hashBuf = _hashCache.acquire();
        //Hash h = _context.sha().calculateHash(fragments, 0, fragmentLength + IV_SIZE);
        _context.sha().calculateHash(fragments, 0, fragmentLength + IV_SIZE, hashBuf.getData(), 0);
        
        //Hash h = _context.sha().calculateHash(target, 0, offset + IV_SIZE);
        //_log.debug("before shift: " + Base64.encode(target));
        // now shiiiiiift
        int distance = PREPROCESSED_SIZE - fragmentLength;
        System.arraycopy(fragments, 0, fragments, distance, fragmentLength);
        
        //if (_log.shouldLog(Log.DEBUG))
        //    _log.debug(msg.getMessageId() + ": fragments begin at " + distance + " (size=" 
        //               + payloadLength + " offset=" + offset +")");
        
        java.util.Arrays.fill(fragments, 0, distance, (byte)0x0);
        //_log.debug("after shift: " + Base64.encode(target));
        
        int offset = 0;
        System.arraycopy(iv, 0, fragments, offset, IV_SIZE);
        offset += IV_SIZE;
        //System.arraycopy(h.getData(), 0, fragments, offset, 4);
        System.arraycopy(hashBuf.getData(), 0, fragments, offset, 4);
        offset += 4;
        //_log.debug("before pad  : " + Base64.encode(target));
        
        _hashCache.release(hashBuf);
        _ivCache.release(ivBuf);
        
        // fits in a single message, so may be smaller than the full size
        int numPadBytes = PREPROCESSED_SIZE     // max 
                          - IV_SIZE             // hmm..
                          - 4                   // 4 bytes of the SHA256
                          - 1                   // the 0x00 after the padding
                          - fragmentLength;     // the size of the fragments (instructions+payload)

        //_log.debug("# pad bytes:  " + numPadBytes + " payloadLength: " + payloadLength + " instructions: " + instructionsLength);

        if (numPadBytes > 0) {
            fillRandomNonZero(fragments, offset, numPadBytes);
            offset += numPadBytes;
        }
       
        fragments[offset] = 0x0; // no more padding
        offset++;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Preprocessing beginning of the fragment instructions at " + offset);
    }

    /**
     *  Efficiently fill with nonzero random data
     *  Don't waste too much entropy or call random() too often.
     *  @since 0.8.5
     */
    private void fillRandomNonZero(byte[] b, int off, int len) {
        // get about as much as we think we will need, overestimate some
        final int est = len + (len / 128) + 3;
        final byte[] tmp = new byte[est];
        _context.random().nextBytes(tmp);
        int extra = len;
        for (int i = 0; i < len; i++) {
            while (tmp[i] == 0) {
                if (extra < est)
                    tmp[i] = tmp[extra++];  // use from the extra we have at the end
                else
                    tmp[i] = (byte)(_context.random().nextInt() & 0xFF);  // waste 3/4 of the entropy
            }
        }
        System.arraycopy(tmp, 0, b, off, len);
    }

    /** is this a follw up byte? */
    private static final byte MASK_IS_SUBSEQUENT = FragmentHandler.MASK_IS_SUBSEQUENT;
    /** how should this be delivered?  shift this 5 the right and get TYPE_* */
    private static final byte MASK_TYPE = FragmentHandler.MASK_TYPE;
    /** is this the first of a fragmented message? */
    private static final byte MASK_FRAGMENTED = FragmentHandler.MASK_FRAGMENTED;

    /**
     *  are there follow up headers?
     *  @deprecated unimplemented
     */
    private static final byte MASK_EXTENDED = FragmentHandler.MASK_EXTENDED;
    private static final byte MASK_TUNNEL = (byte)(FragmentHandler.TYPE_TUNNEL << 5);
    private static final byte MASK_ROUTER = (byte)(FragmentHandler.TYPE_ROUTER << 5);

    protected int writeFirstFragment(TunnelGateway.Pending msg, byte target[], int offset) {
        boolean fragmented = false;
        int origOffset = offset;
        
        int instructionsLength = getInstructionsSize(msg);
        int payloadLength = msg.getData().length - msg.getOffset();
        if (offset + payloadLength + instructionsLength + IV_SIZE + 1 + 4 > PREPROCESSED_SIZE) {
            fragmented = true;
            instructionsLength += 4; // messageId
            payloadLength = PREPROCESSED_SIZE - IV_SIZE - 1 - 4 - instructionsLength - offset;
            if (payloadLength <= 0) 
                throw new RuntimeException("Fragment too small! payloadLen=" + payloadLength 
                                           + " target.length=" + target.length + " offset="+offset
                                           + " msg.length=" + msg.getData().length + " msg.offset=" + msg.getOffset()
                                           + " instructionsLength=" + instructionsLength + " for " + msg);
        }
        if (payloadLength <= 0) 
            throw new RuntimeException("Full size too small! payloadLen=" + payloadLength 
                                       + " target.length=" + target.length + " offset="+offset
                                       + " msg.length=" + msg.getData().length + " msg.offset=" + msg.getOffset()
                                       + " instructionsLength=" + instructionsLength + " for " + msg);
        
        // first fragment, or full message
        target[offset] = 0x0;
        if (msg.getToTunnel() != null)
            target[offset] |= MASK_TUNNEL;
        else if (msg.getToRouter() != null) 
            target[offset] |= MASK_ROUTER;
        if (fragmented)
            target[offset] |= MASK_FRAGMENTED;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("CONTROL: " + Integer.toHexString(target[offset]));

        offset++;

        if (msg.getToTunnel() != null) {
            DataHelper.toLong(target, offset, 4, msg.getToTunnel().getTunnelId());
            offset += 4;
        }
        if (msg.getToRouter() != null) {
            System.arraycopy(msg.getToRouter().getData(), 0, target, offset, Hash.HASH_LENGTH);
            offset += Hash.HASH_LENGTH;
        }
        if (fragmented) {
            DataHelper.toLong(target, offset, 4, msg.getMessageId());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("writing messageId= " + msg.getMessageId() + " at offset " + offset);
            offset += 4;
        }
        DataHelper.toLong(target, offset, 2, payloadLength);
        offset += 2;
        //_log.debug("raw data    : " + Base64.encode(msg.getData()));
        System.arraycopy(msg.getData(), msg.getOffset(), target, offset, payloadLength);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("initial fragment[" + msg.getMessageId() + "/" + msg.getFragmentNumber()+ "/" 
                       + (PREPROCESSED_SIZE - offset - payloadLength) + "/" + payloadLength + "]: " 
                       );
                       //+ Base64.encode(target, offset, payloadLength));

        offset += payloadLength;

        msg.setOffset(msg.getOffset() + payloadLength);
        if (fragmented)
            msg.incrementFragmentNumber();
        return offset;
    }
    
    protected int writeSubsequentFragment(TunnelGateway.Pending msg, byte target[], int offset) {
        boolean isLast = true;
        
        int instructionsLength = getInstructionsSize(msg);
        int payloadLength = msg.getData().length - msg.getOffset();
        if (payloadLength + instructionsLength + IV_SIZE + 1 + 4 > PREPROCESSED_SIZE) {
            isLast = false;
            payloadLength = PREPROCESSED_SIZE - IV_SIZE - 1 - 4 - instructionsLength;
        }
        
        // first fragment, or full message
        target[offset] = 0x0;
        target[offset] |= MASK_IS_SUBSEQUENT;
        target[offset] |= (byte)(msg.getFragmentNumber() << 1); // max 63 fragments
        if (isLast)
            target[offset] |= 1;  
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("CONTROL: " + Integer.toHexString((int)target[offset]) + "/" 
                       + Base64.encode(target, offset, 1) + " at offset " + offset);

        offset++;

        DataHelper.toLong(target, offset, 4, msg.getMessageId());
        offset += 4;
        DataHelper.toLong(target, offset, 2, payloadLength);
        offset += 2;
        System.arraycopy(msg.getData(), msg.getOffset(), target, offset, payloadLength);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("subsequent fragment[" + msg.getMessageId() + "/" + msg.getFragmentNumber()+ "/" 
                       + offset + "/" + payloadLength + "]: " 
                       );
                       //+ Base64.encode(target, offset, payloadLength));

        offset += payloadLength;
        
        if (!isLast)
            msg.incrementFragmentNumber();
        msg.setOffset(msg.getOffset() + payloadLength);
        return offset;
    }

    /**
     *  @return generally 3 or 35 or 39 for first fragment, 7 for subsequent fragments.
     *
     *  Does NOT include 4 for the message ID if the message will be fragmented;
     *  call getInstructionAugmentationSize() for that.
     */
    protected int getInstructionsSize(TunnelGateway.Pending msg) {
        if (msg.getFragmentNumber() > 0) 
            return 7;
        // control byte
        int header = 1;
        // tunnel ID
        if (msg.getToTunnel() != null)
            header += 4;
        // router hash
        if (msg.getToRouter() != null)
            header += 32;
        // size
        header += 2;
        
        return header;
    }
    
    /** @return 0 or 4 */
    protected int getInstructionAugmentationSize(TunnelGateway.Pending msg, int offset, int instructionsSize) {
        int payloadLength = msg.getData().length - msg.getOffset();
        if (offset + payloadLength + instructionsSize + IV_SIZE + 1 + 4 > PREPROCESSED_SIZE) {
            // requires fragmentation, so include the messageId
            return 4;
        }
        return 0;
    }
}
