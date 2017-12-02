package net.i2p.data.i2cp;

import org.junit.runners.Suite;
import org.junit.runner.RunWith;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    AbuseReasonTest.class,
    AbuseSeverityTest.class,
    BandwidthLimitsMessageTest.class,
    CreateLeaseSetMessageTest.class,
    CreateSessionMessageTest.class,
    DestLookupMessageTest.class,
    DestReplyMessageTest.class,
    DestroySessionMessageTest.class,
    DisconnectMessageTest.class,
    GetBandwidthLimitsMessageTest.class,
    GetDateMessageTest.class,
    MessageIdTest.class,
    MessagePayloadMessageTest.class,
    MessageStatusMessageTest.class,
    ReceiveMessageBeginMessageTest.class,
    ReceiveMessageEndMessageTest.class,
    ReconfigureSessionMessageTest.class,
    ReportAbuseMessageTest.class,
    RequestLeaseSetMessageTest.class,
    SendMessageExpiresMessageTest.class,
    SendMessageMessageTest.class,
    SessionConfigTest.class,
    SessionIdTest.class,
    SessionStatusMessageTest.class,
    SetDateMessageTest.class,
})
public class I2CPTestSuite {
}
