package i2p.bote.folder;

import i2p.bote.packet.DataPacket;
import i2p.bote.packet.UniqueId;
import i2p.bote.packet.dht.DhtStorablePacket;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import net.i2p.util.Log;

/**
 * This class stores new files under a random file name with the .pkt extension.
 *
 * @author HungryHobo@mail.i2p
 * @param <T> The type of data stored in this folder
 */
public class PacketFolder<T extends DataPacket> extends Folder<T> {
    protected static final String PACKET_FILE_EXTENSION = ".pkt";
    
    private Log log = new Log(PacketFolder.class);

    public PacketFolder(File storageDir) {
        super(storageDir, PACKET_FILE_EXTENSION);
    }
    
    public void add(DhtStorablePacket packetToStore) {
        String filename = getFilename(packetToStore);
        FileOutputStream outputStream = null;
        try {
            File file = new File(storageDir, filename);
            outputStream = new FileOutputStream(file);
            packetToStore.writeTo(outputStream);
        } catch (Exception e) {
            log.error("Can't save packet to file: <" + filename + ">", e);
        }
        finally {
            if (outputStream != null)
                try {
                    outputStream.close();
                }
                catch (IOException e) {
                    log.error("Can't close file: <" + filename + ">", e);
                }
        }
    }
    
    public void delete(UniqueId packetId) {
        // TODO
    }

    protected String getFilename(DhtStorablePacket packet) {
        return new UniqueId().toBase64() + PACKET_FILE_EXTENSION;
    }
    
    @Override
    @SuppressWarnings("unchecked")
    protected T createFolderElement(File file) throws Exception {
        return (T)DataPacket.createPacket(file);
    }
}