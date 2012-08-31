package net.i2p.router.client;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by jrandom in 2003 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import net.i2p.data.i2cp.AbuseReason;
import net.i2p.data.i2cp.AbuseSeverity;
import net.i2p.data.i2cp.I2CPMessageException;
import net.i2p.data.i2cp.ReportAbuseMessage;
import net.i2p.router.JobImpl;
import net.i2p.router.RouterContext;
import net.i2p.util.Log;

/**
 * Async job to send an abuse message to the client
 *
 */
class ReportAbuseJob extends JobImpl {
    private final Log _log;
    private final ClientConnectionRunner _runner;
    private final String _reason;
    private final int _severity;
    public ReportAbuseJob(RouterContext context, ClientConnectionRunner runner, String reason, int severity) {
        super(context);
        _log = context.logManager().getLog(ReportAbuseJob.class);
        _runner = runner;
        _reason = reason;
        _severity = severity;
    }
    
    public String getName() { return "Report Abuse"; }
    public void runJob() {
        if (_runner.isDead()) return;
        AbuseReason res = new AbuseReason();
        res.setReason(_reason);
        AbuseSeverity sev = new AbuseSeverity();
        sev.setSeverity(_severity);
        ReportAbuseMessage msg = new ReportAbuseMessage();
        msg.setMessageId(null);
        msg.setReason(res);
        msg.setSessionId(_runner.getSessionId());
        msg.setSeverity(sev);
        try {
            _runner.doSend(msg);
        } catch (I2CPMessageException ime) {
            _log.error("Error reporting abuse", ime);
        }
    }
}
