package net.i2p.router.tunnel;
    
import net.i2p.data.Hash;
import net.i2p.data.TunnelId;
import net.i2p.data.i2np.*;
import net.i2p.router.util.CDPQEntry;

/**
 *  Stores all the state for an unsent or partially-sent message
 *
 *  @since 0.9.3
 */
class OutboundGatewayMessage extends PendingGatewayMessage implements CDPQEntry {
    private long _seqNum;
    private final int _priority;
    
    public OutboundGatewayMessage(I2NPMessage message, Hash toRouter, TunnelId toTunnel) {
        super(message, toRouter, toTunnel);
        _priority = getPriority(message);
    }

    /**
     *  For CDPQ
     */
    public void setSeqNum(long num) {
        _seqNum = num;
    }

    /**
     *  For CDPQ
     */
    public long getSeqNum() {
        return _seqNum;
    }

    /**
     *  For CDPQ
     */
    public int getPriority() {
        return _priority;
    }

    /**
     *  This is just for priority in the queue waiting for the fragmenter.
     *  After the fragmenter, they will be OutNetMessages with priority 400.
     *  We use the same 100-500 priority as OutNetMessage so the stats
     *  in CoDelPriorityBlockingQueue work.
     *
     *  We could - perhaps - have BatchedPreprocessor pass the max priority of
     *  any message fragment in a TunnelDataMessage to the OutboundReceiver, to
     *  set the OutNetMessage priority - but that may just make more of an
     *  out-of-order mess and failed reconstruction of fragments.
     */
    private static int getPriority(I2NPMessage message) {
        switch (message.getType()) {

            // tagset/LS reply
            case DeliveryStatusMessage.MESSAGE_TYPE:
                return 1000;

            // building new IB tunnel
            case TunnelBuildMessage.MESSAGE_TYPE:
            case VariableTunnelBuildMessage.MESSAGE_TYPE:
                return 500;

            // LS store
            case DatabaseStoreMessage.MESSAGE_TYPE:
                return 400;

            // LS verify
            case DatabaseLookupMessage.MESSAGE_TYPE:
                return 300;

            // regular data
            case GarlicMessage.MESSAGE_TYPE:
                return 200;

            // these shouldn't go into a OBGW
            case DatabaseSearchReplyMessage.MESSAGE_TYPE:
            case DataMessage.MESSAGE_TYPE:
            case TunnelBuildReplyMessage.MESSAGE_TYPE:
            case TunnelDataMessage.MESSAGE_TYPE:
            case TunnelGatewayMessage.MESSAGE_TYPE:
            case VariableTunnelBuildReplyMessage.MESSAGE_TYPE:
            default:
                return 100;

        }
    }
}
    
