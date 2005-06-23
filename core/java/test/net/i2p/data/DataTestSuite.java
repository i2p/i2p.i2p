package net.i2p.data;

import junit.framework.Test;
import junit.framework.TestSuite;

public class DataTestSuite {
    
    public static Test suite() {
        
        TestSuite suite = new TestSuite("net.i2p.data.DataTestSuite");
        
        suite.addTestSuite(AbuseReasonTest.class);
        suite.addTestSuite(AbuseSeverityTest.class);
        suite.addTestSuite(Base64Test.class);
        suite.addTestSuite(BooleanTest.class);
        suite.addTestSuite(CertificateTest.class);
        suite.addTestSuite(CreateLeaseSetMessageTest.class);
        suite.addTestSuite(CreateSessionMessageTest.class);
        suite.addTestSuite(DataHelperTest.class);
        suite.addTestSuite(DataStructureImplTest.class);
        suite.addTestSuite(DateTest.class);
        suite.addTestSuite(DestinationTest.class);
        suite.addTestSuite(DestroySessionMessageTest.class);
        suite.addTestSuite(DisconnectMessageTest.class);
        suite.addTestSuite(HashTest.class);
        suite.addTestSuite(LeaseSetTest.class);
        suite.addTestSuite(LeaseTest.class);
        suite.addTestSuite(MappingTest.class);
        suite.addTestSuite(MessageIdTest.class);
        suite.addTestSuite(MessagePayloadMessageTest.class);
        suite.addTestSuite(MessageStatusMessageTest.class);
        suite.addTestSuite(PayloadTest.class);
        suite.addTestSuite(PrivateKeyTest.class);
        suite.addTestSuite(PublicKeyTest.class);
        suite.addTestSuite(ReceiveMessageBeginMessageTest.class);
        suite.addTestSuite(ReceiveMessageEndMessageTest.class);
        suite.addTestSuite(ReportAbuseMessageTest.class);
        suite.addTestSuite(RequestLeaseSetMessageTest.class);
        suite.addTestSuite(RouterAddressTest.class);
        suite.addTestSuite(RouterIdentityTest.class);
        suite.addTestSuite(RouterInfoTest.class);
        suite.addTestSuite(SendMessageMessageTest.class);
        suite.addTestSuite(SessionConfigTest.class);
        suite.addTestSuite(SessionIdTest.class);
        suite.addTestSuite(SessionKeyTest.class);
        suite.addTestSuite(SessionStatusMessageTest.class);
        suite.addTestSuite(SignatureTest.class);
        suite.addTestSuite(SigningPrivateKeyTest.class);
        suite.addTestSuite(SigningPublicKeyTest.class);
        suite.addTestSuite(StringTest.class);
        suite.addTestSuite(TunnelIdTest.class);
        suite.addTestSuite(UnsignedIntegerTest.class);
        
        return suite;
    }
}