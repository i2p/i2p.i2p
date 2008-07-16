package net.i2p.data;
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
import net.i2p.data.i2cp.MessageId;
import net.i2p.data.i2cp.ReportAbuseMessage;
import net.i2p.data.i2cp.SessionId;

/**
 * Test harness for loading / storing Hash objects
 *
 * @author jrandom
 */
public class ReportAbuseMessageTest extends StructureTest {
    public DataStructure createDataStructure() throws DataFormatException {
        ReportAbuseMessage msg = new ReportAbuseMessage();
        msg.setMessageId((MessageId)(new MessageIdTest()).createDataStructure());
        msg.setReason((AbuseReason)(new AbuseReasonTest()).createDataStructure());
        msg.setSessionId((SessionId)(new SessionIdTest()).createDataStructure());
        msg.setSeverity((AbuseSeverity)(new AbuseSeverityTest()).createDataStructure());
        return msg; 
    }
    public DataStructure createStructureToRead() { return new ReportAbuseMessage(); }
}
