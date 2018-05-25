package net.i2p.router.web.helpers;

import java.util.HashMap;
import java.util.Map;

import net.i2p.data.DataFormatException;
import net.i2p.data.Hash;
import net.i2p.router.TunnelPoolSettings;
import net.i2p.router.web.FormHandler;
import net.i2p.util.Log;

/**
 * Handler to deal with form submissions from the tunnel config form and act
 * upon the values.
 *
 */
public class ConfigTunnelsHandler extends FormHandler {

    private boolean _shouldSave;
    
    @Override
    protected void processForm() {
        if (_shouldSave) {
            saveChanges();
        } else {
            // noop
        }
    }
    
    public void setShouldsave(String moo) { 
        if ( (moo != null) && (moo.equals(_t("Save changes"))) )
            _shouldSave = true; 
    }
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean saveRequired = false;
        Map<String, String> changes = new HashMap<String, String>();
        
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
            
            Object di = _settings.get(index + ".depthInbound");
            if (di == null) {
                // aliased pools
                index++;
                continue;
            }
            in.setLength(getInt(di));
            out.setLength(getInt(_settings.get(index + ".depthOutbound")));
            in.setLengthVariance(getInt(_settings.get(index + ".varianceInbound")));
            out.setLengthVariance(getInt(_settings.get(index + ".varianceOutbound")));
            in.setQuantity(getInt(_settings.get(index + ".quantityInbound")));
            out.setQuantity(getInt(_settings.get(index + ".quantityOutbound")));
            in.setBackupQuantity(getInt(_settings.get(index + ".backupInbound")));
            out.setBackupQuantity(getInt(_settings.get(index + ".backupOutbound")));
            
            if ("exploratory".equals(poolName)) {
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH, in.getLength()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH, out.getLength()+"");
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH_VARIANCE, in.getLengthVariance()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_LENGTH_VARIANCE, out.getLengthVariance()+"");
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_QUANTITY, in.getQuantity()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_QUANTITY, out.getQuantity()+"");
                changes.put(TunnelPoolSettings.PREFIX_INBOUND_EXPLORATORY + 
                                                   TunnelPoolSettings.PROP_BACKUP_QUANTITY, in.getBackupQuantity()+"");
                changes.put(TunnelPoolSettings.PREFIX_OUTBOUND_EXPLORATORY + 
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
            // the count isn't really correct anyway, since we don't check for actual changes
            //addFormNotice("Updated settings for " + updated + " pools.");
            addFormNotice(_t("Updated settings for all pools."));
        
        if (saveRequired) {
            boolean saved = _context.router().saveConfig(changes, null);
            if (saved) 
                addFormNotice(_t("Exploratory tunnel configuration saved successfully."));
            else
                addFormError(_t("Error saving the configuration (applied but not saved) - please see the error logs."));
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
