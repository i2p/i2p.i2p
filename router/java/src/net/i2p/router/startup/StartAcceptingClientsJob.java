package net.i2p.router.startup;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.i2p.router.ClientManagerFacade;
import net.i2p.router.JobImpl;
import net.i2p.router.JobQueue;
import net.i2p.router.Router;
import net.i2p.util.Clock;
import net.i2p.util.I2PThread;
import net.i2p.util.Log;
import net.i2p.router.RouterContext;

public class StartAcceptingClientsJob extends JobImpl {
    private Log _log;
    
    public StartAcceptingClientsJob(RouterContext context) {
        super(context);
        _log = context.logManager().getLog(StartAcceptingClientsJob.class);
    }
    
    public String getName() { return "Start Accepting Clients"; }
    
    public void runJob() {
        // start up the network database

        _context.clientManager().startup();

        _context.jobQueue().addJob(new ReadConfigJob(_context));
        _context.jobQueue().addJob(new RebuildRouterInfoJob(_context));
        _context.jobQueue().allowParallelOperation();
    }
}
