package i2p.bote.packet;

import static junit.framework.Assert.assertTrue;
import i2p.bote.EmailIdentity;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EmailPacketTest {
    EmailPacket emailPacket;
    EmailIdentity identity;
    String message = "This is a test message. Test 1 2 3 Test";

    @Before
    public void setUp() throws Exception {
        byte[] content = message.getBytes();
        UniqueId deletionKeyPlain = new UniqueId(new byte[] {72, -18, -72, -39, 122, 40, -104, -66, -54, -61, -108, 72, 54, 30, 37, 76, 44, 86, 104, -124, -31, 32, -82, -27, 26, 76, 7, 106, -76, 72, 49, -44}, 0);
        UniqueId deletionKeyEncrypted = new UniqueId(new byte[] {-62, -112, 99, -65, 13, 44, -117, -111, 96, 45, -6, 64, 78, 57, 117, 103, -24, 101, 106, -116, -18, 62, 99, -49, 60, -81, 8, 64, 27, -41, -104, 58}, 0);
        
        byte[] messageIdBytes = new byte[] {2, -69, -24, -109, 1, 69, -122, -69, 113, -68, -90, 55, -28, 105, 97, 125, 70, 51, 58, 14, 2, -13, -53, 90, -29, 36, 67, 36, -94, -108, -125, 11, 123};
        UniqueId messageId = new UniqueId(messageIdBytes, 0);
        
        int fragmentIndex = 0;
        int numFragments = 1;
        
        String base64Identity = "piYT1uJ3O8~bBPZmTvehMbp3-Zksg5enhvIlp2X8txqL25l0WdQMWwyt30UAOVQqxGdnMPTqqjh~-zoa~rCQORo~J1gRxLwCX9LlHQqaIimJilrbN-rhKy4Xlft054wbgQjLSC-WICE4W64KDfitwRzdr7lV6lz~0KFiZ8erZ-~WPMG1CgWEku9lILQUdUHyFBguPcK9oPDq7oGBuFGy8w0CvAq7ex3nmbL7zQVA~VqILtOGeGK2fidCuuofj4AQsTcXmH9O0nxZGCIJBhf~4EWmazvxu8XVB8pabNQvRDbmFu6q85JTwmxC45lCjqNw30hp8q2zoqP-zchjWOrxFUhSumpBdD0xXJR~qmhejh4WnuRnnam9j3fcxH5i~T7xWgmvIbpZEI4kyc9VEbXbLI7k-bU2A6sdP-AGt5~TjGLcxpdsPnOLRXO-Dsi7E9-3Kc84s4TmdpEJdtHn1dxYyeeT-ysVOqXjv5w5Cuk0XJpUIJG8n7aXHpNb-QLxPD3yAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADWF3qnAX-p41Po~VNmOUzS-Yt~noD8-e~L3P5rZXBWf-XtB4hkloo6m1jwqphEdf1";
        identity = new EmailIdentity(base64Identity);

        emailPacket = new EmailPacket(content, deletionKeyPlain, deletionKeyEncrypted, messageId, fragmentIndex, numFragments, identity);
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void toByteArrayAndBack() throws Exception {
        byte[] arrayA = emailPacket.toByteArray();
        byte[] arrayB = new EmailPacket(arrayA).toByteArray();
        assertTrue("The two arrays differ!", Arrays.equals(arrayA, arrayB));
    }
    
    @Test
    public void testEncryptionDecryption() throws Exception {
        emailPacket.setContent(null);
        emailPacket.decrypt(identity);
        byte[] arrayA = emailPacket.getContent();
        byte[] arrayB = message.getBytes();
        assertTrue("Email message differs after decryption!", Arrays.equals(arrayA, arrayB));
    }
}