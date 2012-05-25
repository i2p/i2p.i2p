package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;

import net.i2p.data.i2cp.SessionConfig;
import net.i2p.router.ClientTunnelSettings;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Given an established connection, walk through the process of establishing the
 * lease set.  This requests the TunnelManagerFacade to build tunnels for the
 * client and then once thats done (asynchronously) it requests a lease set from
 * the client
 *
 */
class CreateSessionJob extends JobImpl {
    private Log _log;
    private ClientConnectionRunner _runner;
    
    public CreateSessionJob(RouterContext context, ClientConnectionRunner runner) {
        super(context);
        _log = context.logManager().getLog(CreateSessionJob.class);
        _runner = runner;
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("CreateSessionJob for runner " + _runner + " / config: " + _runner.getConfig());
    }
    
    public String getName() { return "Request tunnels for a new client"; }
    public void runJob() {
        SessionConfig cfg = _runner.getConfig();
        if ( (cfg == null) || (cfg.getDestination() == null) ) {
            if (_log.shouldLog(Log.ERROR))
                _log.error("No session config on runner " + _runner);
            return;
        }
        if (_log.shouldLog(Log.INFO))
            _log.info("Requesting lease set for destination " + cfg.getDestination().calculateHash().toBase64());
        ClientTunnelSettings settings = new ClientTunnelSettings();
        Properties props = new Properties();
        
        // We're NOT going to force all clients to use the router's defaults, since that may be
        // excessive.  This means that unless the user says otherwise, we'll be satisfied with whatever
        // is available.  Otherwise, when the router starts up, if there aren't sufficient tunnels with the
        // adequate number of hops, the user will have to wait.  Once peer profiles are persistent, we can
        // reenable this, since on startup we'll have a sufficient number of high enough ranked peers to
        // tunnel through.  (perhaps).
        
        // XXX take the router's defaults
        // XXX props.putAll(Router.getInstance().getConfigMap());
        
        // override them by the client's settings
        props.putAll(cfg.getOptions());
        
        // and load 'em up (using anything not yet set as the software defaults)
        settings.readFromProperties(props);
        getContext().tunnelManager().buildTunnels(cfg.getDestination(), settings);
    }
}
