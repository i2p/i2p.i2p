package i2p.bote.network.kademlia;

public class KademliaConstants {
    public static final int K = 2;   // Number of redundant storage nodes.
    public static final int S = 3;   // The size of the sibling list for S/Kademlia.
//    public static final int B = 5;   // This is the value from the original Kademlia paper.
    public static final int B = 1;
    public static final int ALPHA = 3;   // According to the literature, this is the optimum choice for alpha.
    public static final int REFRESH_TIMEOUT = 3600;
    public static final int REPLICATE_INTERVAL = 3600;   // TODO would it be better for REPLICATE_INTERVAL to be slightly longer than REFRESH_TIMEOUT?

    private KademliaConstants() { }
}