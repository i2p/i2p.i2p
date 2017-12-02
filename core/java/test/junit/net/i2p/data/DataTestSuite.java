package net.i2p.data;

import org.junit.runners.Suite;
import org.junit.runner.RunWith;

@RunWith(Suite.class)
@Suite.SuiteClasses({
    Base64Test.class,
    BooleanTest.class,
    CertificateTest.class,
    DataHelperTest.class,
    DataStructureImplTest.class,
    DateTest.class,
    DestinationTest.class,
    HashTest.class,
    LeaseSetTest.class,
    LeaseTest.class,
    MappingTest.class,
    PayloadTest.class,
    PrivateKeyTest.class,
    PublicKeyTest.class,
    SessionKeyTest.class,
    SignatureTest.class,
    SigningPrivateKeyTest.class,
    SigningPublicKeyTest.class,
    StringTest.class,
    TunnelIdTest.class,
    UnsignedIntegerTest.class,
})
public class DataTestSuite {
}
