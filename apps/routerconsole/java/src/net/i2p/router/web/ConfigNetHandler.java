package net.i2p.router.web;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileOutputStream;

import java.net.URL;
import java.net.URLConnection;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.i2p.util.Log;
import net.i2p.time.Timestamper;

import net.i2p.router.RouterContext;
import net.i2p.router.ClientTunnelSettings;

/**
 * Handler to deal with form submissions from the main config form and act
 * upon the values.
 *
 */
public class ConfigNetHandler extends FormHandler {
    private String _hostname;
    private boolean _guessRequested;
    private boolean _reseedRequested;
    private boolean _saveRequested;
    private boolean _timeSyncEnabled;
    private String _port;
    private String _inboundRate;
    private String _inboundBurst;
    private String _outboundRate;
    private String _outboundBurst;
    private String _reseedFrom;
    
    public void ConfigNetHandler() {
        _guessRequested = false;
        _reseedRequested = false;
        _saveRequested = false;
        _timeSyncEnabled = false;
    }
    
    protected void processForm() {
        if (_guessRequested) {
            guessHostname();
        } else if (_reseedRequested) {
            reseed();
        } else if (_saveRequested) {
            saveChanges();
        } else {
            // noop
        }
    }
    
    public void setGuesshost(String moo) { _guessRequested = true; }
    public void setReseed(String moo) { _reseedRequested = true; }
    public void setSave(String moo) { _saveRequested = true; }
    public void setEnabletimesync(String moo) { _timeSyncEnabled = true; }
    
    public void setHostname(String hostname) { 
        _hostname = (hostname != null ? hostname.trim() : null); 
    }
    public void setPort(String port) { 
        _port = (port != null ? port.trim() : null); 
    }
    public void setInboundrate(String rate) { 
        _inboundRate = (rate != null ? rate.trim() : null); 
    }
    public void setInboundburstfactor(String factor) { 
        _inboundBurst = (factor != null ? factor.trim() : null); 
    }
    public void setOutboundrate(String rate) { 
        _outboundRate = (rate != null ? rate.trim() : null); 
    }
    public void setOutboundburstfactor(String factor) { 
        _outboundBurst = (factor != null ? factor.trim() : null); 
    }
    public void setReseedfrom(String url) { 
        _reseedFrom = (url != null ? url.trim() : null); 
    }

    private static final String IP_PREFIX = "<h1>Your IP is ";
    private static final String IP_SUFFIX = " <br></h1>";
    private void guessHostname() {
        BufferedReader reader = null;
        try {
            URL url = new URL("http://www.whatismyip.com/");
            URLConnection con = url.openConnection();
            con.connect();
            reader = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String line = null;
            while ( (line = reader.readLine()) != null) {
                if (line.startsWith(IP_PREFIX)) {
                    int end = line.indexOf(IP_SUFFIX);
                    if (end == -1) {
                        addFormError("Unable to guess the host (BAD_SUFFIX)");
                        return;
                    }
                    String ip = line.substring(IP_PREFIX.length(), end);
                    addFormNotice("Host guess: " + ip);
                    return;
                }
            }
            addFormError("Unable to guess the host (NO_PREFIX)");
        } catch (IOException ioe) {
            addFormError("Unable to guess the host (IO_ERROR)");
            _context.logManager().getLog(ConfigNetHandler.class).error("Unable to guess the host", ioe);
        } finally {
            if (reader != null) try { reader.close(); } catch (IOException ioe) {}
        }
    }
    
    private static final String DEFAULT_SEED_URL = "http://dev.i2p.net/i2pdb/";
    /**
     * Reseed has been requested, so lets go ahead and do it.  Fetch all of
     * the routerInfo-*.dat files from the specified URL (or the default) and
     * save them into this router's netDb dir.
     *
     */
    private void reseed() {
        String seedURL = DEFAULT_SEED_URL;
        if (_reseedFrom != null)
            seedURL = _reseedFrom;
        try {
            URL dir = new URL(seedURL);
            String content = new String(readURL(dir));
            Set urls = new HashSet();
            int cur = 0;
            while (true) {
                int start = content.indexOf("href=\"routerInfo-", cur);
                if (start < 0)
                    break;

                int end = content.indexOf(".dat\">", start);
                String name = content.substring(start+"href=\"routerInfo-".length(), end);
                urls.add(name);
                cur = end + 1;
            }

            int fetched = 0;
            for (Iterator iter = urls.iterator(); iter.hasNext(); ) {
                fetchSeed(seedURL, (String)iter.next());
                fetched++;
            }
            addFormNotice("Reseeded with " + fetched + " peers");
        } catch (Throwable t) {
            _context.logManager().getLog(ConfigNetHandler.class).error("Error reseeding", t);
            addFormError("Error reseeding (RESEED_EXCEPTION)");
        }
    }
    
    private void fetchSeed(String seedURL, String peer) throws Exception {
        URL url = new URL(seedURL + "/routerInfo-" + peer + ".dat");

        byte data[] = readURL(url);
        writeSeed(peer, data);
    }
    
    private byte[] readURL(URL url) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        URLConnection con = url.openConnection();
        InputStream in = con.getInputStream();
        byte buf[] = new byte[1024];
        while (true) {
            int read = in.read(buf);
            if (read < 0)
                break;
            baos.write(buf, 0, read);
        }
        in.close();
        return baos.toByteArray();
    }
    
    private void writeSeed(String name, byte data[]) throws Exception {
        // props taken from KademliaNetworkDatabaseFacade...
        String dirName = _context.getProperty("router.networkDatabase.dbDir", "netDb");
        File netDbDir = new File(dirName);
        if (!netDbDir.exists()) {
            boolean ok = netDbDir.mkdirs();
            if (ok)
                addFormNotice("Network database directory created: " + dirName);
            else
                addFormNotice("Error creating network database directory: " + dirName);
        }
        FileOutputStream fos = new FileOutputStream(new File(netDbDir, "routerInfo-" + name + ".dat"));
        fos.write(data);
        fos.close();
    }
    
    /**
     * The user made changes to the network config and wants to save them, so
     * lets go ahead and do so.
     *
     */
    private void saveChanges() {
        boolean restartRequired = false;
        
        if ( (_hostname != null) && (_hostname.length() > 0) ) {
            String oldHost = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_HOSTNAME);
            if ( (oldHost == null) || (!oldHost.equalsIgnoreCase(_hostname)) ) {
                _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_HOSTNAME, _hostname);
                addFormNotice("Updating hostname from " + oldHost + " to " + _hostname);
                restartRequired = true;
            }
        }
        if ( (_port != null) && (_port.length() > 0) ) {
            String oldPort = _context.router().getConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_PORT);
            if ( (oldPort == null) || (!oldPort.equalsIgnoreCase(_port)) ) {
                _context.router().setConfigSetting(ConfigNetHelper.PROP_I2NP_TCP_PORT, _port);
                addFormNotice("Updating TCP port from " + oldPort + " to " + _port);
                restartRequired = true;
            }
        }
        
        updateRates();
        
        if (_timeSyncEnabled) {
            // Time sync enable, means NOT disabled 
            _context.router().setConfigSetting(Timestamper.PROP_DISABLED, "false");
        } else {
            _context.router().setConfigSetting(Timestamper.PROP_DISABLED, "true");
        }
        
        boolean saved = _context.router().saveConfig();
        if (saved) 
            addFormNotice("Configuration saved successfully");
        else
            addFormNotice("Error saving the configuration (applied but not saved) - please see the error logs");
        
        if (restartRequired) {
            addFormNotice("Performing a soft restart");
            _context.router().restart();
            addFormNotice("Soft restart complete");
        }
    }
    
    private void updateRates() {
        boolean updated = false;
        if ( (_inboundRate != null) && (_inboundRate.length() > 0) ) {
            _context.router().setConfigSetting(ConfigNetHelper.PROP_INBOUND_KBPS, _inboundRate);
            updated = true;
        }
        if ( (_outboundRate != null) && (_outboundRate.length() > 0) ) {
            _context.router().setConfigSetting(ConfigNetHelper.PROP_OUTBOUND_KBPS, _outboundRate);
            updated = true;
        }
        
        String inRate = _context.router().getConfigSetting(ConfigNetHelper.PROP_INBOUND_KBPS);
        
        if (_inboundBurst != null) {
            int rateKBps = 0;
            int burstSeconds = 0;
            try {
                rateKBps = Integer.parseInt(inRate);
                burstSeconds = Integer.parseInt(_inboundBurst);
            } catch (NumberFormatException nfe) {
                // ignore
            }
            if ( (rateKBps > 0) && (burstSeconds > 0) ) {
                int kb = rateKBps * burstSeconds;
                _context.router().setConfigSetting(ConfigNetHelper.PROP_INBOUND_BURST, "" + kb);
                updated = true;
            }
        }
        
        String outRate = _context.router().getConfigSetting(ConfigNetHelper.PROP_OUTBOUND_KBPS);
        
        if (_outboundBurst != null) {
            int rateKBps = 0;
            int burstSeconds = 0;
            try {
                rateKBps = Integer.parseInt(outRate);
                burstSeconds = Integer.parseInt(_outboundBurst);
            } catch (NumberFormatException nfe) {
                // ignore
            }
            if ( (rateKBps > 0) && (burstSeconds > 0) ) {
                int kb = rateKBps * burstSeconds;
                _context.router().setConfigSetting(ConfigNetHelper.PROP_OUTBOUND_BURST, "" + kb);
                updated = true;
            }
        }
        
        if (updated)
            addFormNotice("Updated bandwidth limits");
    }
}
