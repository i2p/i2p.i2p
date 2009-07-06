package net.i2p.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.util.Log;

public class Address extends DataStructureImpl {
    private final static Log _log = new Log(Address.class);
    private String _hostname;
    private Destination _destination;
    
    public Address() {
        _hostname = null;
        _destination = null;
    }

    public String getHostname() {
        return _hostname;
    }
    
    public void setHostname(String hostname) {
        _hostname = hostname;
    }
    
    public Destination getDestination() {
        return _destination;
    }
    
    public void setDestination(Destination destination) {
        _destination = destination;
    }
    
    public void setDestination(String base64) {
        try {
            Destination result = new Destination();
            result.fromBase64(base64);
            _destination = result;
        } catch (DataFormatException dfe) {
            _destination = null;
        }
    }
    
    public void readBytes(InputStream in) throws DataFormatException,
            IOException {
        _hostname = DataHelper.readString(in);
        _destination = new Destination();
        _destination.readBytes(in);
    }
    
    public void writeBytes(OutputStream out) throws DataFormatException,
            IOException {
        if ((_hostname == null) || (_destination == null)) 
            throw new DataFormatException("Not enough data to write address");
        DataHelper.writeString(out, _hostname);
        _destination.writeBytes(out);
    }
    
    @Override
    public boolean equals(Object obj) {
        if ((obj == null) || !(obj instanceof Address)) return false;
        Address addr = (Address) obj;
        return DataHelper.eq(_hostname, addr.getHostname())
        && DataHelper.eq(_destination, addr.getDestination());
    }
    
    @Override
    public int hashCode() {
        return DataHelper.hashCode(getHostname()) 
        + DataHelper.hashCode(getDestination());
    }
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder(64);
        buf.append("[Address: ");
        buf.append("\n\tHostname: ").append(getHostname());
        buf.append("\n\tDestination: ").append(getDestination());
        buf.append("]");
        return buf.toString();
    }

}
