package i2p.bote.packet;

import i2p.bote.Util;
import i2p.bote.folder.FolderElement;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.i2p.util.Log;

/**
 * The superclass of all "payload" packet types.
 *
 * @author HungryHobo@mail.i2p
 */
public abstract class DataPacket extends I2PBotePacket implements FolderElement {
    private static Log log = new Log(DataPacket.class);

    private File file;
    
    public DataPacket() {
    }
    
    @Override
    public void writeTo(OutputStream outputStream) throws Exception {
        outputStream.write(toByteArray());
    }
    
    public static DataPacket createPacket(File file) {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            DataPacket packet = createPacket(Util.readInputStream(inputStream));
            return packet;
        }
        catch (IOException e) {
            log.error("Can't read packet file: " + file.getAbsolutePath(), e);
            return null;
        }
        finally {
            try {
                inputStream.close();
            }
            catch (IOException e) {
                log.error("Can't close stream.", e);
            }
        }
    }
    
    /**
     * Creates a {@link DataPacket} object from its byte array representation.
     * If there is an error, <code>null</code> is returned.
     * @param data
     * @return
     */
    public static DataPacket createPacket(byte[] data) {
        char packetTypeCode = (char)data[0];   // first byte of a data packet is the packet type code
        Class<? extends I2PBotePacket> packetType = decodePacketTypeCode(packetTypeCode);
        if (packetType==null || !DataPacket.class.isAssignableFrom(packetType)) {
            log.error("Type code is not a DataPacket type code: <" + packetTypeCode + ">");
            return null;
        }
        
        Class<? extends DataPacket> dataPacketType = packetType.asSubclass(DataPacket.class);
        try {
            return dataPacketType.getConstructor(byte[].class).newInstance(data);
        }
        catch (Exception e) {
            log.warn("Can't instantiate packet for type code <" + packetTypeCode + ">", e);
            return null;
        }
    }

    // FolderElement implementation
    @Override
    public File getFile() {
        return file;
    }

    @Override
    public void setFile(File file) {
        this.file = file;
    }
}