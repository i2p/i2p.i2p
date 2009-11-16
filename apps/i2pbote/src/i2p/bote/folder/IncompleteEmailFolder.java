package i2p.bote.folder;

import i2p.bote.packet.DataPacket;
import i2p.bote.packet.Email;
import i2p.bote.packet.EmailPacket;
import i2p.bote.packet.I2PBotePacket;
import i2p.bote.packet.UniqueId;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

import net.i2p.util.Log;

/**
 * File name format: <email dest hash>_<message id>.pkt
 *
 * @author HungryHobo@mail.i2p
 */
public class IncompleteEmailFolder extends DhtPacketFolder<EmailPacket> {
    private Log log = new Log(IncompleteEmailFolder.class);
    private EmailFolder inbox;

    public IncompleteEmailFolder(File storageDir, EmailFolder inbox) {
        super(storageDir);
        this.inbox = inbox;
    }
    
    public void add(EmailPacket packetToStore) {
        super.add(packetToStore);
        
        // TODO possible optimization: if getNumFragments == 1, no need to check for other packet files
        File[] finishedPacketFiles = getAllMatchingFiles(packetToStore.getMessageId());
        
        // if all packets of the email are available, assemble them into an email
        if (finishedPacketFiles.length == packetToStore.getNumFragments())
            assemble(finishedPacketFiles);
    }
    
    private void assemble(File[] packetFiles) {
        // No need to do this in a separate thread
        new AssembleTask(packetFiles, inbox).run();
    }
    
    private File[] getAllMatchingFiles(UniqueId messageId) {
        final String base64Id = messageId.toBase64();
        
        return storageDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                int _index = name.indexOf('_');
                if (_index >= 0)
                    return name.substring(_index).startsWith(base64Id);
                else {
                    log.error("Invalid file name: <" + name + ">, directory: <" + dir.getAbsolutePath() + ">");
                    return false;
                }
            }
        });
    }
    
    // Overridden to include the message id in the file name
    protected String getFilename(EmailPacket packet) {
        return packet.getDhtKey().toBase64() + packet.getMessageId() + PACKET_FILE_EXTENSION;
    }

    // Overridden because the file format is different than what the superclass uses
    @Override
    protected boolean filenameMatches(String filename, String base64DhtKey) {
        return filename.startsWith(base64DhtKey);
    }
    
    /**
     * Makes a set of {@link EmailPacket}s into an {@link Email}, stores the email in the <code>inbox</code>
     * folder, and deletes the packet files.
     *
     * @author HungryHobo@mail.i2p
     */
    private class AssembleTask implements Runnable {
        File[] packetFiles;
        
        public AssembleTask(File[] packetFiles, EmailFolder inbox) {
            this.packetFiles = packetFiles;
        }

        @Override
        public void run() {
            EmailPacket[] packets = getEmailPackets(packetFiles).toArray(new EmailPacket[0]);
            
            // sort by fragment index
            Arrays.sort(packets, new Comparator<EmailPacket>() {
                @Override
                public int compare(EmailPacket packet1, EmailPacket packet2) {
                    return new Integer(packet1.getFragmentIndex()).compareTo(packet2.getFragmentIndex());
                }
            });

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try {
                for (EmailPacket packet: packets)
                    outputStream.write(packet.getContent());
                Email email = new Email(outputStream.toByteArray());
                inbox.add(email);
                
                // delete packets
                for (File file: packetFiles)
                    if (!file.delete())
                        log.warn("Email packet file not deleted: <" + file.getAbsolutePath() + ">");
            }
            catch (Exception e) {
                log.error("Error assembling/storing email, or deleting email packets. ", e);
                return;
            }
        }
        
        private Collection<EmailPacket> getEmailPackets(File[] files) {
            Collection<EmailPacket> packets = new ArrayList<EmailPacket>();
            for (File file: files) {
                I2PBotePacket packet = DataPacket.createPacket(file);
                if (packet instanceof EmailPacket)
                    packets.add((EmailPacket)packet);
                else
                    log.error("Non-Email Packet found in the IncompleteEmailFolder, file: <" + file.getAbsolutePath() + ">");
            }
            return packets;
        }
    }
}