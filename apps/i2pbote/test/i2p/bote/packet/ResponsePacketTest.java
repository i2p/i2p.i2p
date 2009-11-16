package i2p.bote.packet;

import static junit.framework.Assert.assertTrue;
import i2p.bote.EmailIdentity;

import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ResponsePacketTest {
    private ResponsePacket responsePacket;

    @Before
    public void setUp() throws Exception {
        byte[] packetIdBytes = new byte[] {120, 120, -8, -88, 21, 126, 46, -61, 18, -101, 15, 53, 20, -44, -112, 42, 86, -117, 30, -96, -66, 33, 71, -55, -102, -78, 78, -82, -105, 66, -116, 43};
        UniqueId packetId = new UniqueId(packetIdBytes, 0);
        
        responsePacket = new ResponsePacket(createDataPacket(), StatusCode.OK, packetId);
    }

    private DataPacket createDataPacket() throws NoSuchAlgorithmException {
        byte[] dataToStore = new byte[] {2, 109, -80, -37, -106, 83, -33, -39, -94, -76, -112, -98, 99, 25, -61, 44, -92, -85, 1, 10, -128, -2, -27, -86, -126, -33, -11, 42, 56, 3, -97, -101, 111, 7, -96, 25, 121, 74, 89, -40, -33, 82, -50, -18, 49, 106, 13, -121, 53, -83, -2, 35, -7, 71, -71, 26, 90, 1};
        
        UniqueId deletionKeyPlain = new UniqueId(new byte[] {72, -18, -72, -39, 122, 40, -104, -66, -54, -61, -108, 72, 54, 30, 37, 76, 44, 86, 104, -124, -31, 32, -82, -27, 26, 76, 7, 106, -76, 72, 49, -44}, 0);
        UniqueId deletionKeyEncrypted = new UniqueId(new byte[] {-62, -112, 99, -65, 13, 44, -117, -111, 96, 45, -6, 64, 78, 57, 117, 103, -24, 101, 106, -116, -18, 62, 99, -49, 60, -81, 8, 64, 27, -41, -104, 58}, 0);
        byte[] messageIdBytes = new byte[] {2, -69, -24, -109, 1, 69, -122, -69, 113, -68, -90, 55, -28, 105, 97, 125, 70, 51, 58, 14, 2, -13, -53, 90, -29, 36, 67, 36, -94, -108, -125, 11, 123};
        UniqueId messageId = new UniqueId(messageIdBytes, 0);
        
        String base64Identity = "piYT1uJ3O8~bBPZmTvehMbp3-Zksg5enhvIlp2X8txqL25l0WdQMWwyt30UAOVQqxGdnMPTqqjh~-zoa~rCQORo~J1gRxLwCX9LlHQqaIimJilrbN-rhKy4Xlft054wbgQjLSC-WICE4W64KDfitwRzdr7lV6lz~0KFiZ8erZ-~WPMG1CgWEku9lILQUdUHyFBguPcK9oPDq7oGBuFGy8w0CvAq7ex3nmbL7zQVA~VqILtOGeGK2fidCuuofj4AQsTcXmH9O0nxZGCIJBhf~4EWmazvxu8XVB8pabNQvRDbmFu6q85JTwmxC45lCjqNw30hp8q2zoqP-zchjWOrxFUhSumpBdD0xXJR~qmhejh4WnuRnnam9j3fcxH5i~T7xWgmvIbpZEI4kyc9VEbXbLI7k-bU2A6sdP-AGt5~TjGLcxpdsPnOLRXO-Dsi7E9-3Kc84s4TmdpEJdtHn1dxYyeeT-ysVOqXjv5w5Cuk0XJpUIJG8n7aXHpNb-QLxPD3yAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADWF3qnAX-p41Po~VNmOUzS-Yt~noD8-e~L3P5rZXBWf-XtB4hkloo6m1jwqphEdf1";
        EmailIdentity identity = new EmailIdentity(base64Identity);

        return new EmailPacket(dataToStore, deletionKeyPlain, deletionKeyEncrypted, messageId, 0, 1, identity);
    }
    
    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void toByteArrayAndBack() throws Exception {
        byte[] arrayA = responsePacket.toByteArray();
        byte[] arrayB = new ResponsePacket(arrayA).toByteArray();
        assertTrue(Arrays.equals(arrayA, arrayB));
    }
}