package net.i2p.router;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Iterator;
import java.util.Properties;

/**
 * Wrap up the client settings specifying their tunnel criteria
 *
 */
public class ClientTunnelSettings {
    private TunnelPoolSettings _inboundSettings;
    private TunnelPoolSettings _outboundSettings;
    
    public ClientTunnelSettings() {
        _inboundSettings = new TunnelPoolSettings();
        _inboundSettings.setIsInbound(true);
        _inboundSettings.setIsExploratory(false);
        _outboundSettings = new TunnelPoolSettings();
        _outboundSettings.setIsInbound(false);
        _outboundSettings.setIsExploratory(false);
    }
    
    public TunnelPoolSettings getInboundSettings() { return _inboundSettings; }
    public void setInboundSettings(TunnelPoolSettings settings) { _inboundSettings = settings; }
    public TunnelPoolSettings getOutboundSettings() { return _outboundSettings; }
    public void setOutboundSettings(TunnelPoolSettings settings) { _outboundSettings = settings; }
    
    public void readFromProperties(Properties props) {
        _inboundSettings.readFromProperties("inbound.", props);
        _outboundSettings.readFromProperties("outbound.", props);
	}
    
    public void writeToProperties(Properties props) {
        if (props == null) return;
        _inboundSettings.writeToProperties("inbound.", props);
        _outboundSettings.writeToProperties("outbound.", props);
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        Properties p = new Properties();
        writeToProperties(p);
        buf.append("Client tunnel settings:\n");
        buf.append("====================================\n");
        for (Iterator iter = p.keySet().iterator(); iter.hasNext(); ) {
            String name = (String)iter.next();
            String val  = p.getProperty(name);
            buf.append(name).append(" = [").append(val).append("]\n");
        }
        buf.append("====================================\n");
        return buf.toString();
    }
}
