/*
 * QKademliaComparator.java
 *
 * Created on March 30, 2005, 12:30 PM
 */

package net.i2p.aum.q;

import java.util.*;
import java.math.*;

/**
 * implements a Comparator class which compares two QPeerRec objects
 * for kademlia-closeness to a given base64 sha hash value
 */
public class QKademliaComparator implements Comparator {
    
    QNode node;
    BigInteger hashed;
    
    /**
     * Creates a kademlia comparator, which given a base64 sha256 hash
     * of something, can compare two nodes for their kademlia-closeness to
     * that hash
     * @param node a QNode object - needed for access to its base64 routines
     * @param base64hash - string - a base64 representation of the sha256 hash
     * of anything
     */
    public QKademliaComparator(QNode node, String base64hash) {
    
        this.node = node;
        hashed = new BigInteger(node.base64Dec(base64hash).getBytes());
    }
    
    /**
     * compares two given QPeerRec objects for how close each one's ID
     * is to the stored hash
     */
    public int compare(Object o1, Object o2) {

        QPeer peer1 = (QPeer)o1;
        QPeer peer2 = (QPeer)o2;

        String id1 = peer1.getId();
        String id2 = peer2.getId();
        
        BigInteger i1 = new BigInteger(id1.getBytes());
        BigInteger i2 = new BigInteger(id2.getBytes());

        BigInteger xor1 = i1.xor(hashed);
        BigInteger xor2 = i2.xor(hashed);

        return xor1.compareTo(xor2);
    }
    
}

