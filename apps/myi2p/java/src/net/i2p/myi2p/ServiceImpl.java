package net.i2p.myi2p;

import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.myi2p.Service;
import net.i2p.myi2p.Node;
import net.i2p.myi2p.MyI2PMessage;

/**
 * Base service implementation
 *
 */
public abstract class ServiceImpl implements Service {
    private I2PAppContext _context;
    private Node _node;
    private Properties _options;
    private String _serviceType;
    
    public ServiceImpl() {
        _context = null;
        _node = null;
        _options = null;
        _serviceType = null;
    }
    
    // base inspectors / mutators
    public Node getNode() { return _node; }
    public void setNode(Node node) { _node = node; }
    public I2PAppContext getContext() { return _context; }
    public void setContext(I2PAppContext context) { _context = context; }
    public Properties getOptions() { return _options; }
    public void setOptions(Properties opts) { _options = opts; }
    public String getType() { return _serviceType; }
    public void setType(String type) { _serviceType = type; }
}
