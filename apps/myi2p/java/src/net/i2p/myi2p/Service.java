package net.i2p.myi2p;

import java.util.Properties;
import net.i2p.I2PAppContext;

/**
 * Defines a service that can operate within a MyI2P node, responding to 
 * messages and performing whatever tasks are necessary.
 *
 */
public interface Service {
    /** what type of message will this service respond to? */
    public String getType();
    public void setType(String type);
    
    /** what node is this service hooked into */
    public Node getNode();
    public void setNode(Node node);
    
    /** what options specific to this node does the service have? */
    public Properties getOptions();
    public void setOptions(Properties opts);
    
    /** give the service a scope */
    public I2PAppContext getContext();
    public void setContext(I2PAppContext context);
    
    /** called when a message is received for the service */
    public void receiveMessage(MyI2PMessage msg);
    
    /** start the service up - the node is ready */
    public void startup();
    /** shut the service down - the node is going offline */
    public void shutdown();
}
