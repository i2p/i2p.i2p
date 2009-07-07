package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.ByteArray;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;

/** 
 * Do the simplest thing possible for preprocessing - for each message available,
 * turn it into the minimum number of fragmented preprocessed blocks, sending 
 * each of those out.  This does not coallesce message fragments or delay for more
 * optimal throughput.
 *
 */
public class TrivialPreprocessor implements TunnelGateway.QueuePreprocessor {
    protected I2PAppContext _context;
    private Log _log;
    
    public static final int PREPROCESSED_SIZE = 1024;
    protected static final int IV_SIZE = HopProcessor.IV_LENGTH;
    protected static final ByteCache _dataCache = ByteCache.getInstance(32, PREPROCESSED_SIZE);
    protected static final ByteCache _ivCache = ByteCache.getInstance(128, IV_SIZE);
    protected static final ByteCache _hashCache = ByteCache.getInstance(128, Hash.HASH_LENGTH);
    
    public TrivialPreprocessor(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TrivialPreprocessor.class);
    }
  
    /** how long do we want to wait before flushing */
    public long getDelayAmount() { return 0; }
  
    /**
     * Return true if there were messages remaining, and we should queue up
     * a delayed flush to clear them
     *
     */
    public boolean preprocessQueue(List pending, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        long begin = System.currentTimeMillis();
        StringBuilder buf = null;
        if (_log.shouldLog(Log.DEBUG)) {
            buf = new StringBuilder(256);
            buf.append("Trivial preprocessing of ").append(pending.size()).append(" ");
        }
        while (pending.size() > 0) {
            TunnelGateway.Pending msg = (TunnelGateway.Pending)pending.remove(0);
            long beforePreproc = System.currentTimeMillis();
            byte preprocessed[][] = preprocess(msg);
            long afterPreproc = System.currentTimeMillis();
            if (buf != null)
                buf.append("preprocessed into " + preprocessed.length + " fragments after " + (afterPreproc-beforePreproc) + ". ");
            for (int i = 0; i < preprocessed.length; i++) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Preprocessed: fragment " + i + "/" + (preprocessed.length-1) + " in " 
                               + msg.getMessageId() + ": "
                               + " send through " + sender + " receive with " + rec);
                               //Base64.encode(preprocessed[i]));
                long beforeSend = System.currentTimeMillis();
                long id = sender.sendPreprocessed(preprocessed[i], rec);
                long afterSend = System.currentTimeMillis();
                if (buf != null)
                    buf.append("send of " + msg.getMessageId() + " took " + (afterSend-beforeSend) + ". ");
                msg.addMessageId(id);
            }
            notePreprocessing(msg.getMessageId(), msg.getFragmentNumber(), preprocessed.length, msg.getMessageIds(), null);
            if (preprocessed.length != msg.getFragmentNumber() + 1) {
                throw new RuntimeException("wtf, preprocessed " + msg.getMessageId() + " into " 
                                           + msg.getFragmentNumber() + "/" + preprocessed.length + " fragments, size = "
                                           + msg.getData().length);
            }
            if (buf != null)
                buf.append("all fragments sent after " + (System.currentTimeMillis()-afterPreproc) + ". ");
        }
        if (buf != null) {
            buf.append("queue preprocessed after " + (System.currentTimeMillis()-begin) + ".");
            _log.debug(buf.toString());
        }
        return false;
    }
    
    protected void notePreprocessing(long messageId, int numFragments, int totalLength, List messageIds, String msg) {}
    
    private byte[][] preprocess(TunnelGateway.Pending msg) {
        List fragments = new ArrayList(1);

        while (msg.getOffset() < msg.getData().length) {
            fragments.add(preprocessFragment(msg));
            //if (_log.shouldLog(Log.DEBUG))
            //    _log.debug("\n\nafter preprocessing fragment\n\n");
        }

        byte rv[][] = new byte[fragments.size()][];
        for (int i = 0; i < fragments.size(); i++)
            rv[i] = (byte[])fragments.get(i);
        return rv;
    }
    
    /**
     * Preprocess the next available fragment off the given one in phases:
     * First, write it out as { instructions + payload + random IV }, calculate the 
     * SHA256 of that, then move the instructions + payload to the end 
     * of the target, setting IV as the beginning.  Then add the necessary random pad
     * bytes after the IV, followed by the first 4 bytes of that SHA256, lining up
     * exactly to meet the beginning of the instructions. (i hope)
     *
     */
    private byte[] preprocessFragment(TunnelGateway.Pending msg) {
        byte target[] = _dataCache.acquire().getData();

        int offset = 0;
        if (msg.getOffset() <= 0)
            offset = writeFirstFragment(msg, target, offset);
        else
            offset = writeSubsequentFragment(msg, target, offset);
        
        preprocess(target, offset);
        return target;
    }
    
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

        int paddingRemaining = numPadBytes;
        while (paddingRemaining > 0) {
            byte b = (byte)(_context.random().nextInt() & 0xFF);
            if (b != 0x00) {
                fragments[offset] = b;
                offset++;
                paddingRemaining--;
            }
        }
       
        fragments[offset] = 0x0; // no more padding
        offset++;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Preprocessing beginning of the fragment instructions at " + offset);
    }

    /** is this a follw up byte? */
    private static final byte MASK_IS_SUBSEQUENT = FragmentHandler.MASK_IS_SUBSEQUENT;
    /** how should this be delivered?  shift this 5 the right and get TYPE_* */
    private static final byte MASK_TYPE = FragmentHandler.MASK_TYPE;
    /** is this the first of a fragmented message? */
    private static final byte MASK_FRAGMENTED = FragmentHandler.MASK_FRAGMENTED;
    /** are there follow up headers? */
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

    protected int getInstructionsSize(TunnelGateway.Pending msg) {
        if (msg.getFragmentNumber() > 0) 
            return 7;
        int header = 1;
        if (msg.getToTunnel() != null)
            header += 4;
        if (msg.getToRouter() != null)
            header += 32;
        header += 2;
        
        return header;
    }
    
    protected int getInstructionAugmentationSize(TunnelGateway.Pending msg, int offset, int instructionsSize) {
        int payloadLength = msg.getData().length - msg.getOffset();
        if (offset + payloadLength + instructionsSize + IV_SIZE + 1 + 4 > PREPROCESSED_SIZE) {
            // requires fragmentation, so include the messageId
            return 4;
        }
        return 0;
    }
}
