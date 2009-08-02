package net.i2p.router.web;

import java.util.HashMap;
import java.util.Map;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.util.Log;

/**
 * Handler to deal with form submissions from the tunnel config form and act
 * upon the values.  Holy crap, this is UUUUGLY
 *
 */
public class ConfigTunnelsHandler extends FormHandler {
    private Log _log;
    private Map _settings;
    private boolean _shouldSave;
    
    public ConfigTunnelsHandler() {
        _shouldSave = false;
    }
    
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }
    
    public void setShouldsave(String moo) { 
        if ( (moo != null) && (moo.equals("Save changes")) )
            _shouldSave = true; 
    }
    
    public void setSettings(Map settings) { _settings = new HashMap(settings); }
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        _log = _context.logManager().getLog(ConfigTunnelsHandler.class);
        boolean saveRequired = false;
        
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("Saving changes, with props = " + _settings + ".");
        
        int updated = 0;
        int index = 0;
        while (true) {
            Object val = _settings.get("pool." + index);
            if (val == null) break;
            Hash client = new Hash();
            
            String poolName = (val instanceof String ? (String)val : ((String[])val)[0]);
            
            TunnelPoolSettings in = null;
            TunnelPoolSettings out = null;
            if ("exploratory".equals(poolName)) {
                in = _context.tunnelManager().getInboundSettings();
                out = _context.tunnelManager().getOutboundSettings();
            } else {
                try {
                    client.fromBase64(poolName);
                } catch (DataFormatException dfe) {
                    addFormError("Internal error (pool name could not resolve - " + poolName + ").");
                    index++;
                    continue;
                }
                in = _context.tunnelManager().getInboundSettings(client);
                out = _context.tunnelManager().getOutboundSettings(client);
            }
            
            if ( (in == null) || (out == null) ) {
                addFormError("Internal error (pool settings cound not be found for " + poolName + ").");
                index++;
                continue;
            }
            
            in.setLength(getInt(_settings.get(index + ".depthInbound")));
            out.setLength(getInt(_settings.get(index + ".depthOutbound")));
            in.setLengthVariance(getInt(_settings.get(index + ".varianceInbound")));
            out.setLengthVariance(getInt(_settings.get(index + ".varianceOutbound")));
            in.setQuantity(getInt(_settings.get(index + ".quantityInbound")));
            out.setQuantity(getInt(_settings.get(index + ".quantityOutbound")));
            in.setBackupQuantity(getInt(_settings.get(index + ".backupInbound")));
            out.setBackupQuantity(getInt(_settings.get(index + ".backupOutbound")));
            
            if ("exploratory".equals(poolName)) {
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH, in.getLength()+"");
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH, out.getLength()+"");
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH_VARIANCE, in.getLengthVariance()+"");
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH_VARIANCE, out.getLengthVariance()+"");
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_QUANTITY, in.getQuantity()+"");
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_QUANTITY, out.getQuantity()+"");
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_BACKUP_QUANTITY, in.getBackupQuantity()+"");
                _context.router().setConfigSetting(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_BACKUP_QUANTITY, out.getBackupQuantity()+"");
            }
            
            if ("exploratory".equals(poolName)) {
                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Inbound exploratory settings: " + in);
                    _log.debug("Outbound exploratory settings: " + out);
                }
                _context.tunnelManager().setInboundSettings(in);
                _context.tunnelManager().setOutboundSettings(out);
            } else {
                if (_log.shouldLog(Log.DEBUG)) {
                    _log.debug("Inbound settings for " + client.toBase64() + ": " + in);
                    _log.debug("Outbound settings for " + client.toBase64() + ": " + out);
                }
                _context.tunnelManager().setInboundSettings(client, in);
                _context.tunnelManager().setOutboundSettings(client, out);
            }
            
            updated++;
            saveRequired = true;
            index++;
        }
        
        if (updated > 0)
            addFormNotice("Updated settings for " + updated + " pools.");
        
        if (saveRequired) {
            boolean saved = _context.router().saveConfig();
            if (saved) 
                addFormNotice("Exploratory tunnel configuration saved successfully.");
            else
                addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs.");
        }
    }
    private static final int getInt(Object val) { 
        if (val == null) return 0;
        String str = null;
        if (val instanceof String)
            str = (String)val;
        else 
            str = ((String[])val)[0];
            
        if (str.trim().length() <= 0) return 0;
        try { return Integer.parseInt(str); } catch (NumberFormatException nfe) { return 0; }
    }
}
