package net.i2p.router.tunnel;

import java.util.ArrayList;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.Base64;
import net.i2p.data.DataHelper;
import net.i2p.data.Hash;
import net.i2p.data.i2np.I2NPMessage;
import net.i2p.util.Log;

/** 
 * Do the simplest thing possible for preprocessing - for each message available,
 * turn it into the minimum number of fragmented preprocessed blocks, sending 
 * each of those out.  This does not coallesce message fragments or delay for more
 * optimal throughput.
 *
 */
public class TrivialPreprocessor implements TunnelGateway.QueuePreprocessor {
    private I2PAppContext _context;
    private Log _log;
    
    private static final int PREPROCESSED_SIZE = 1024;
    private static final int IV_SIZE = HopProcessor.IV_LENGTH;
    
    public TrivialPreprocessor(I2PAppContext ctx) {
        _context = ctx;
        _log = ctx.logManager().getLog(TrivialPreprocessor.class);
    }
    
    public boolean preprocessQueue(List pending, TunnelGateway.Sender sender, TunnelGateway.Receiver rec) {
        while (pending.size() > 0) {
            TunnelGateway.Pending msg = (TunnelGateway.Pending)pending.remove(0);
            byte preprocessed[][] = preprocess(msg);
            for (int i = 0; i < preprocessed.length; i++) {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Preprocessed: " + Base64.encode(preprocessed[i]));
                sender.sendPreprocessed(preprocessed[i], rec);
            }
        }
        return false;
    }
    
    private byte[][] preprocess(TunnelGateway.Pending msg) {
        List fragments = new ArrayList(1);

        while (msg.getOffset() < msg.getData().length) {
            fragments.add(preprocessFragment(msg));
            _log.debug("\n\nafter preprocessing fragment\n\n");
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
        if (msg.getOffset() <= 0)
            return preprocessFirstFragment(msg);
        else
            return preprocessSubsequentFragment(msg);
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
    
    private byte[] preprocessFirstFragment(TunnelGateway.Pending msg) {
        boolean fragmented = false;
        byte iv[] = new byte[IV_SIZE];
        _context.random().nextBytes(iv);
        
        byte target[] = new byte[PREPROCESSED_SIZE];
        
        int instructionsLength = getInstructionsSize(msg);
        int payloadLength = msg.getData().length;
        if (payloadLength + instructionsLength + IV_SIZE + 1 + 4 > PREPROCESSED_SIZE) {
            fragmented = true;
            instructionsLength += 4; // messageId
            payloadLength = PREPROCESSED_SIZE - IV_SIZE - 1 - 4 - instructionsLength;
        }
        
        int offset = 0;
        
        // first fragment, or full message
        target[offset] = 0x0;
        if (msg.getToTunnel() != null)
            target[offset] |= MASK_TUNNEL;
        else if (msg.getToRouter() != null) 
            target[offset] |= MASK_ROUTER;
        if (fragmented)
            target[offset] |= MASK_FRAGMENTED;
        
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
            _log.debug("writing messageId= " + msg.getMessageId() + " at offset " + offset);
            offset += 4;
        }
        DataHelper.toLong(target, offset, 2, payloadLength);
        offset += 2;
        //_log.debug("raw data    : " + Base64.encode(msg.getData()));
        System.arraycopy(msg.getData(), 0, target, offset, payloadLength);
        _log.debug("fragment[" + msg.getFragmentNumber()+ "/" + (PREPROCESSED_SIZE - offset - payloadLength) + "/" + payloadLength + "]: " + Base64.encode(target, offset, payloadLength));

        offset += payloadLength;
        
        // payload ready, now H(instructions+payload+IV)
        System.arraycopy(iv, 0, target, offset, IV_SIZE);
        Hash h = _context.sha().calculateHash(target, 0, offset + IV_SIZE);
        _log.debug("before shift: " + Base64.encode(target));
        // now shiiiiiift
        int distance = PREPROCESSED_SIZE - offset;
        System.arraycopy(target, 0, target, distance, offset);
        
        _log.debug("fragments begin at " + distance + " (size=" + payloadLength + " offset=" + offset +")");
        
        java.util.Arrays.fill(target, 0, distance, (byte)0x0);
        _log.debug("after shift: " + Base64.encode(target));
        
        offset = 0;
        System.arraycopy(iv, 0, target, offset, IV_SIZE);
        offset += IV_SIZE;
        System.arraycopy(h.getData(), 0, target, offset, 4);
        offset += 4;
        //_log.debug("before pad  : " + Base64.encode(target));
        
        if (!fragmented) {
            // fits in a single message, so may be smaller than the full size
           int numPadBytes = PREPROCESSED_SIZE     // max 
                             - IV_SIZE             // hmm..
                             - 4                   // 4 bytes of the SHA256
                             - 1                   // the 0x00 after the padding
                             - payloadLength       // the, er, payload
                             - instructionsLength; // wanna guess?

           //_log.debug("# pad bytes:  " + numPadBytes + " payloadLength: " + payloadLength + " instructions: " + instructionsLength);
           
           for (int i = 0; i < numPadBytes; i++) {
               if (false) {
                   target[offset] = 0x0;
                   offset++;
               } else { 
                   // wouldn't it be nice if random could write to an array?
                   byte rnd = (byte)_context.random().nextInt();
                   if (rnd != 0x0) {
                       target[offset] = rnd;
                       offset++;
                   } else {
                       i--;
                   }
               }
           }
        }
        target[offset] = 0x0; // no padding here
        offset++;
        
        msg.setOffset(payloadLength);
        msg.incrementFragmentNumber();
        return target;
    }
    
    private byte[] preprocessSubsequentFragment(TunnelGateway.Pending msg) {
        boolean isLast = true;
        byte iv[] = new byte[IV_SIZE];
        _context.random().nextBytes(iv);
        
        byte target[] = new byte[PREPROCESSED_SIZE];
        
        int instructionsLength = getInstructionsSize(msg);
        int payloadLength = msg.getData().length - msg.getOffset();
        if (payloadLength + instructionsLength + IV_SIZE + 1 + 4 > PREPROCESSED_SIZE) {
            isLast = false;
            payloadLength = PREPROCESSED_SIZE - IV_SIZE - 1 - 4 - instructionsLength;
        }
        
        int offset = 0;
        
        // first fragment, or full message
        target[offset] = 0x0;
        target[offset] |= MASK_IS_SUBSEQUENT;
        target[offset] |= (byte)(msg.getFragmentNumber() << 1); // max 63 fragments
        if (isLast)
            target[offset] |= 1;  
        
        _log.debug("CONTROL: " + Integer.toHexString((int)target[offset]) + "/" + Base64.encode(target, offset, 1) + " at offset " + offset);

        offset++;

        DataHelper.toLong(target, offset, 4, msg.getMessageId());
        offset += 4;
        DataHelper.toLong(target, offset, 2, payloadLength);
        offset += 2;
        System.arraycopy(msg.getData(), msg.getOffset(), target, offset, payloadLength);
        _log.debug("fragment[" + msg.getFragmentNumber()+ "/" + offset + "/" + payloadLength + "]: " + Base64.encode(target, offset, payloadLength));

        offset += payloadLength;

        // payload ready, now H(instructions+payload+IV)
        System.arraycopy(iv, 0, target, offset, IV_SIZE);
        Hash h = _context.sha().calculateHash(target, 0, offset + IV_SIZE);
        // now shiiiiiift
        int distance = PREPROCESSED_SIZE - offset;
        System.arraycopy(target, 0, target, distance, offset);
                        
        _log.debug("fragments begin at " + distance + " (size=" + payloadLength + " offset=" + offset +")");

        offset = 0;
        System.arraycopy(iv, 0, target, 0, IV_SIZE);
        offset += IV_SIZE;
        
        System.arraycopy(h.getData(), 0, target, offset, 4);
        offset += 4;
        
        if (isLast) {
            // this is the last message, so may be smaller than the full size
           int numPadBytes = PREPROCESSED_SIZE     // max 
                             - IV_SIZE             // hmm..
                             - 4                   // 4 bytes of the SHA256
                             - 1                   // the 0x00 after the padding
                             - payloadLength       // the, er, payload
                             - instructionsLength; // wanna guess?

           for (int i = 0; i < numPadBytes; i++) {
               // wouldn't it be nice if random could write to an array?
               byte rnd = (byte)_context.random().nextInt();
               if (rnd != 0x0) {
                   target[offset] = rnd;
                   offset++;
               } else {
                   i--;
               }
           }

           _log.debug("# pad bytes: " + numPadBytes);
        }
        target[offset] = 0x0; // end of padding
        offset++;
        
        msg.setOffset(msg.getOffset() + payloadLength);
        msg.incrementFragmentNumber();
        return target;
    }
    
    private int getInstructionsSize(TunnelGateway.Pending msg) {
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
    
}
