package net.i2p.myi2p;

import net.i2p.data.Destination;

/**
 * Packages up a message for delivery.  The raw format of the message within a
 * repliable datagram is
 *  <code>MyI2P $maj.$min $service $type\n$payload</code>
 * where <code>$maj.$min</code> is currently 1.0, $service is the type of MyI2P
 * service, $type is the type of message within that service, and $payload is 
 * the data specific to that type.
 *
 */
public class MyI2PMessage {
    private Destination _peer;
    private String _service;
    private String _type;
    private byte[] _payload;
    
    private static final byte[] MESSAGE_PREFIX = "MyI2P 1.0 ".getBytes();
    
    /**
     * Build a new MyI2P message to be sent.
     *
     * @param to address to send the message
     * @param service what MyI2P service is involved
     * @param type type of message within that service is involved
     * @param data payload of the message to deliver
     */
    public MyI2PMessage(Destination to, String service, String type, byte data[]) {
        _peer = to;
        _service = service;
        _type = type;
        _payload = data;
    }
    
    /**
     * Read in the MyI2P data from the given datagram info.
     *
     * @param from authenticated from address
     * @param dgramPayload raw MyI2P formatted message
     * @throws IllegalArgumentException if the message is not a valid MyI2P message
     */
    public MyI2PMessage(Destination from, byte dgramPayload[]) throws IllegalArgumentException {
        _peer = from;
        int index = 0;
        while (index < dgramPayload.length) {
            if (index >= MESSAGE_PREFIX.length) break;
            if (dgramPayload[index] != MESSAGE_PREFIX[index])
                throw new IllegalArgumentException("Invalid payload (not a MyI2P message)");
            index++;
        }
        
        // $service $type\n$payload
        StringBuffer service = new StringBuffer(8);
        while (index < dgramPayload.length) {
            if (dgramPayload[index] == ' ') {
                _service = service.toString();
                index++;
                break;
            } else if (dgramPayload[index] == '\n') {
                throw new IllegalArgumentException("Ran into newline while reading the service");
            } else {
                service.append((char)dgramPayload[index]);
                index++;
            }
        }
        
        StringBuffer type = new StringBuffer(8);
        while (index < dgramPayload.length) {
            if (dgramPayload[index] == '\n') {
                _type = type.toString();
                index++;
                break;
            } else {
                service.append((char)dgramPayload[index]);
                index++;
            }
        }
        
        _payload = new byte[dgramPayload.length-index];
        System.arraycopy(dgramPayload, index, _payload, 0, _payload.length);
    }
   
    /** who is this message from or who is it going to? */
    public Destination getPeer() { return _peer; }
    /** what MyI2P service is this bound for (addressBook, blog, etc)? */
    public String getServiceType() { return _service; }
    /** within that service, what type of message is this? */
    public String getMessageType() { return _type; }
    /** what is the raw data for the particular message? */
    public byte[] getPayload() { return _payload; }
    
    /**
     * Retrieve the raw payload, suitable for wrapping in an I2PDatagramMaker 
     * and sending to another MyI2P node.
     *
     * @throws IllegalStateException if some data is missing
     */
    public byte[] toRawPayload() throws IllegalStateException {
        if (_service == null) throw new IllegalStateException("Service is null");
        if (_type == null) throw new IllegalStateException("Type is null");
        if (_payload == null) throw new IllegalStateException("Payload is null");
        
        byte service[] = _service.getBytes();
        byte type[] = _type.getBytes();
        byte rv[] = new byte[MESSAGE_PREFIX.length + service.length + 1 + type.length + 1 + _payload.length];
        System.arraycopy(MESSAGE_PREFIX, 0, rv, 0, MESSAGE_PREFIX.length);
        System.arraycopy(service, 0, rv, MESSAGE_PREFIX.length, service.length);
        rv[MESSAGE_PREFIX.length + service.length] = ' ';
        System.arraycopy(type, 0, rv, MESSAGE_PREFIX.length + service.length + 1, type.length);
        rv[MESSAGE_PREFIX.length + service.length + 1 + type.length] = '\n';
        System.arraycopy(_payload, 0, rv, MESSAGE_PREFIX.length + service.length + 1 + type.length + 1, _payload.length);
        return rv;
    }
}
