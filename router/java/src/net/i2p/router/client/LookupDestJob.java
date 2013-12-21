/*
 * Released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 */
package net.i2p.router.client;

import java.util.Locale;

import net.i2p.data.Base32;
import net.i2p.data.Destination;
import net.i2p.data.Hash;
import net.i2p.data.LeaseSet;
import net.i2p.data.i2cp.DestReplyMessage;
import net.i2p.data.i2cp.HostReplyMessage;
import net.i2p.data.i2cp.I2CPMessage;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;

/**
 * Look up the lease of a hash, to convert it to a Destination for the client.
 * Or, since 0.9.10, lookup a host name in the naming service.
 */
class LookupDestJob extends JobImpl {
    private final ClientConnectionRunner _runner;
    private final long _reqID;
    private final long _timeout;
    private final Hash _hash;
    private final String _name;

    private static final long DEFAULT_TIMEOUT = 15*1000;

    public LookupDestJob(RouterContext context, ClientConnectionRunner runner, Hash h) {
        this(context, runner, -1, DEFAULT_TIMEOUT, h, null);
    }

    /**
     *  One of h or name non-null
     *  @param reqID must be >= 0 if name != null
     *  @since 0.9.10
     */
    public LookupDestJob(RouterContext context, ClientConnectionRunner runner,
                         long reqID, long timeout, Hash h, String name) {
        super(context);
        if ((h == null && name == null) ||
            (h != null && name != null) ||
            (reqID < 0 && name != null))
            throw new IllegalArgumentException();
        _runner = runner;
        _reqID = reqID;
        _timeout = timeout;
        if (name != null && name.length() == 60) {
            // convert a b32 lookup to a hash lookup
            String nlc = name.toLowerCase(Locale.US);
            if (nlc.endsWith(".b32.i2p")) {
                byte[] b = Base32.decode(nlc.substring(0, 52));
                if (b != null && b.length == Hash.HASH_LENGTH) {
                    h = Hash.create(b);
                    name = null;
                }
            }
        }
        _hash = h;
        _name = name;
    }
    
    public String getName() { return _name != null ?
                                     "HostName Lookup for Client" :
                                     "LeaseSet Lookup for Client";
    }

    public void runJob() {
        if (_name != null) {
            // inline, ignore timeout
            Destination d = getContext().namingService().lookup(_name);
            if (d != null)
                returnDest(d);
            else
                returnFail();
        } else {
            DoneJob done = new DoneJob(getContext());
            getContext().netDb().lookupLeaseSet(_hash, done, done, _timeout);
        }
    }

    private class DoneJob extends JobImpl {
        public DoneJob(RouterContext enclosingContext) { 
            super(enclosingContext);
        }
        public String getName() { return "LeaseSet Lookup Reply to Client"; }
        public void runJob() {
            LeaseSet ls = getContext().netDb().lookupLeaseSetLocally(_hash);
            if (ls != null)
                returnDest(ls.getDestination());
            else
                returnFail();
        }
    }

    private void returnDest(Destination d) {
        I2CPMessage msg;
        if (_reqID >= 0)
            msg = new HostReplyMessage(d, _reqID);
        else
            msg = new DestReplyMessage(d);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {}
    }

    /**
     *  Return the failed hash so the client can correlate replies with requests
     *  @since 0.8.3
     */
    private void returnFail() {
        I2CPMessage msg;
        if (_reqID >= 0)
            msg = new HostReplyMessage(HostReplyMessage.RESULT_FAILURE, _reqID);
        else
            msg = new DestReplyMessage(_hash);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {}
    }
}
