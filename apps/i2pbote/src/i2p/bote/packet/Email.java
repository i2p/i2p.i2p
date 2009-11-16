package i2p.bote.packet;

import i2p.bote.EmailDestination;
import i2p.bote.folder.FolderElement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import net.i2p.util.Log;

import com.nettgryppa.security.HashCash;

public class Email extends MimeMessage implements FolderElement {
    private static final int MAX_BYTES_PER_PACKET = 30 * 1024;
    private static final String[] HEADER_WHITELIST = new String[] {
        "From", "Sender", "To", "CC", "BCC", "Reply-To", "Subject", "MIME-Version", "Content-Type", "Content-Transfer-Encoding",
        "Message-Id", "In-Reply-To"
    };
    
    private Log log = new Log(Email.class);
    private String filename;
    private UniqueId messageId;

    public Email() {
        super(Session.getDefaultInstance(new Properties()));
        messageId = new UniqueId();
    }

    /**
     * Creates an Email object from an InputStream containing a MIME email.
     * 
     * @param inputStream
     * @throws MessagingException
     * @see MimeMessage(Session, InputStream)

     */
    public Email(InputStream inputStream) throws MessagingException {
        super(Session.getDefaultInstance(new Properties()), inputStream);
        messageId = new UniqueId();
    }

    /**
     * Creates an Email object from a byte array containing a MIME email.
     * 
     * @param bytes
     * @throws MessagingException
     */
     public Email(byte[] bytes) throws MessagingException {
         super(Session.getDefaultInstance(new Properties()), new ByteArrayInputStream(bytes));
         messageId = new UniqueId();
     }

    // TODO
    public void setHashCash(HashCash hashCash) {
        // add hashCash to header
    }

    /**
     * Called by <code>saveChanges()</code>, see JavaMail JavaDoc.
     */
    @Override
    protected void updateHeaders() throws MessagingException {
        setHeader("Message-Id", getMessageID());
    }
    
    /**
     * Returns a message ID that conforms to RFC822, but doesn't reveal the sender's
     * domain or user name.
     */
    @Override
    public String getMessageID() {
        return messageId.toBase64() + "@i2p";
    }

    /**
     * Converts the email into one or more email packets.
     * 
     * @param destination
     * @return
     * @throws IOException
     * @throws MessagingException
     */
    public Collection<EmailPacket> createEmailPackets(EmailDestination destination) throws IOException, MessagingException {
        ArrayList<EmailPacket> packets = new ArrayList<EmailPacket>();
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        writeTo(outputStream);
        byte[] emailArray = outputStream.toByteArray();
        
        int fragmentIndex = 0;
        int blockStart = 0;   // the array index where the next block of data starts
        while (true) {
            int blockSize = Math.min(emailArray.length-blockStart, MAX_BYTES_PER_PACKET);
            if (blockSize <= 0)
                break;
            else {
                // make a new array with the right length
                byte[] block = new byte[blockSize];
                System.arraycopy(emailArray, blockStart, block, 0, blockSize);
                UniqueId deletionKeyPlain = new UniqueId();
                UniqueId deletionKeyEncrypted = new UniqueId(deletionKeyPlain);   // encryption happens later
                EmailPacket packet = new EmailPacket(block, deletionKeyPlain, deletionKeyEncrypted, messageId, fragmentIndex, 0, destination);   // we'll set the # of fragments in a minute
                packets.add(packet);
                fragmentIndex++;
                blockStart += blockSize;
            }
        }
        
        // set fragment count
        int numFragments = fragmentIndex;
        for (EmailPacket packet: packets)
            packet.setNumFragments(numFragments);
        
        return packets;
    }

/*    private byte[] toByteArray() throws MessagingException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            writeTo(outputStream);
        }
        catch (IOException e) {
            log.error("Can't write to ByteArrayOutputStream.", e);
        }
        return outputStream.toByteArray();
    }*/

    /**
     * Removes all mail headers except the ones in <code>HEADER_WHITELIST</code>.
     * @throws MessagingException 
     */
    public void scrubHeaders() throws MessagingException {
        // TODO does the superclass remove BCC addresses (except the recipient's)? if not, do it in here.
        @SuppressWarnings("unchecked")
        Enumeration<String> headersToRemove = getNonMatchingHeaders(HEADER_WHITELIST);
        while (headersToRemove.hasMoreElements()) {
            String headerName = headersToRemove.nextElement();
            removeHeader(headerName);
        }
    }

    // FolderElement implementation
    @Override
    public File getFile() {
        // TODO Auto-generated method stub
        return null;
    }

    // FolderElement implementation
    @Override
    public void setFile(File file) {
        // TODO Auto-generated method stub
        
    }
}