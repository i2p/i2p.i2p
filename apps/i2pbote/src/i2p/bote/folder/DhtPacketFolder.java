package i2p.bote.folder;

import i2p.bote.network.DhtStorageHandler;
import i2p.bote.packet.dht.DhtStorablePacket;

import java.io.File;
import java.io.FilenameFilter;

import net.i2p.data.Hash;
import net.i2p.util.Log;

/**
 * This class uses dht keys for file names.
 *
 * @author HungryHobo@mail.i2p
 * @param <T> The type of DHT data stored in this folder
 */
public class DhtPacketFolder<T extends DhtStorablePacket> extends PacketFolder<T> implements DhtStorageHandler {
    private Log log = new Log(DhtPacketFolder.class);

    public DhtPacketFolder(File storageDir) {
        super(storageDir);
    }
    
    @Override
    public void store(DhtStorablePacket packetToStore) {
        add(packetToStore);
    }
    
    @Override
    public DhtStorablePacket retrieve(Hash dhtKey) {
        final String base64Key = dhtKey.toBase64();
        
        File[] files = storageDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return filenameMatches(name, base64Key);
            }
        });
        
        if (files.length > 1)
            log.warn("More than one packet files found for DHT key " + dhtKey);
        if (files.length > 0) {
            File file = files[0];
            return DhtStorablePacket.createPacket(file);
        }
        return null;
    }

    protected boolean filenameMatches(String filename, String base64DhtKey) {
        return filename.startsWith(base64DhtKey);
    }
    
    @Override
    protected String getFilename(DhtStorablePacket packet) {
        return packet.getDhtKey().toBase64() + PACKET_FILE_EXTENSION;
    }
}