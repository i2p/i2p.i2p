package i2p.bote.packet;

import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class I2PBotePacketTest {

    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
    }

    @Test
    public void testDecodePacketTypeCode() throws Exception {
        Field allPacketTypesField = I2PBotePacket.class.getDeclaredField("ALL_PACKET_TYPES");
        allPacketTypesField.setAccessible(true);
        Class<? extends I2PBotePacket>[] allPacketTypes = (Class<? extends I2PBotePacket>[])allPacketTypesField.get(null);
        
        for (Class<? extends I2PBotePacket> packetType: allPacketTypes) {
            TypeCode typeCode = packetType.getAnnotation(TypeCode.class);
            System.out.println("Testing decodePacketTypeCode on " + packetType);
            assertTrue(I2PBotePacket.decodePacketTypeCode(typeCode.value()).equals(packetType));
        }
    }
}