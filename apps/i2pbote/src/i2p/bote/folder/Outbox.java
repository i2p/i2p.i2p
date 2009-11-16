package i2p.bote.folder;

import i2p.bote.packet.Email;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import javax.mail.MessagingException;

import net.i2p.util.Log;

/**
 * Stores emails in a directory on the filesystem. For each email, two files are created; the actual
 * email and a status file.
 * Status files and email files have the same name, except for the extension.
 * Even emails that need to be fragmented are stored as a whole.
 * Message IDs are used for filenames.
 * 
 * Status files contain a status for each recipient address.
 * 
 * @author HungryHobo@mail.i2p
 *
 */
public class Outbox extends EmailFolder implements Iterable<Email> {
	private static final String STATUS_FILE_EXTENSION = ".status";
//	private static final String PARAM_QUEUE_DATE = "X-QueueDate";
	private static final Log log = new Log(Outbox.class);
	
	public Outbox(File storageDir) {
		super(storageDir);
	}
	
	// store one email file + one status file.
	@Override
	public void add(Email email) throws MessagingException, IOException {
        // write out the email file
	    super.add(email);
		
		// collect info for status file
		String queueDate = String.valueOf(System.currentTimeMillis());
		
		// write out the status file
		File statusFile = getStatusFile(email);
		FileWriter statusFileWriter = new FileWriter(statusFile);
		statusFileWriter.write(queueDate);
		statusFileWriter.close();
	}
	
	private File getStatusFile(Email email) throws MessagingException {
		return new File(storageDir, email.getMessageID() + STATUS_FILE_EXTENSION);
	}

	// delete an email file + the status file
    @Override
    public void delete(Email email) throws MessagingException {
	    super.delete(email);
	    
        if (!getStatusFile(email).delete())
            log.error("Cannot delete file: '" + getStatusFile(email) + "'");
    }

	/**
	 * 
	 * @param email
	 * @param relayInfo A 0-length array means no relays were used, i.e. the email was sent directly to the recipient.
	 * @param statusMessage
	 */
	public void updateStatus(Email email, int[] relayInfo, String statusMessage) {
		// TODO write out a new status file. filename is the msg id, statusMessage goes into the file.
	}
}