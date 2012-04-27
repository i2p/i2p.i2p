package net.i2p.data.i2cp;

import junit.framework.Test;
import junit.framework.TestSuite;

public class I2CPTestSuite {
    
    public static Test suite() {
        
        TestSuite suite = new TestSuite("net.i2p.data.i2cp.I2CPTestSuite");
        
        suite.addTestSuite(AbuseReasonTest.class);
        suite.addTestSuite(AbuseSeverityTest.class);
        suite.addTestSuite(BandwidthLimitsMessageTest.class);
        suite.addTestSuite(CreateLeaseSetMessageTest.class);
        suite.addTestSuite(CreateSessionMessageTest.class);
        suite.addTestSuite(DestLookupMessageTest.class);
        suite.addTestSuite(DestReplyMessageTest.class);
        suite.addTestSuite(DestroySessionMessageTest.class);
        suite.addTestSuite(DisconnectMessageTest.class);
        suite.addTestSuite(GetBandwidthLimitsMessageTest.class);
        suite.addTestSuite(GetDateMessageTest.class);
        suite.addTestSuite(MessageIdTest.class);
        suite.addTestSuite(MessagePayloadMessageTest.class);
        suite.addTestSuite(MessageStatusMessageTest.class);
        suite.addTestSuite(ReceiveMessageBeginMessageTest.class);
        suite.addTestSuite(ReceiveMessageEndMessageTest.class);
        suite.addTestSuite(ReconfigureSessionMessageTest.class);
        suite.addTestSuite(ReportAbuseMessageTest.class);
        suite.addTestSuite(RequestLeaseSetMessageTest.class);
        suite.addTestSuite(SendMessageExpiresMessageTest.class);
        suite.addTestSuite(SendMessageMessageTest.class);
        suite.addTestSuite(SessionConfigTest.class);
        suite.addTestSuite(SessionIdTest.class);
        suite.addTestSuite(SessionStatusMessageTest.class);
        suite.addTestSuite(SetDateMessageTest.class);
        
        return suite;
    }
}
