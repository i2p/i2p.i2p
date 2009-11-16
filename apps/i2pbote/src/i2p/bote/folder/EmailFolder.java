package i2p.bote.folder;

import i2p.bote.packet.Email;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import javax.mail.MessagingException;

import net.i2p.util.Log;

/**
 * Stores emails in a directory on the filesystem. Each email is stored in one file.
 * The filename is the message Id plus an extension.
 * 
 * @author HungryHobo@mail.i2p
 */
public class EmailFolder extends Folder<Email> {
    protected static final String EMAIL_FILE_EXTENSION = ".mail";
    
    private Log log = new Log(EmailFolder.class);
    
    public EmailFolder(File storageDir) {
        super(storageDir, EMAIL_FILE_EXTENSION);
    }
    
    // store an email file
    public void add(Email email) throws MessagingException, IOException {
        // write out the email file
        File emailFile = getEmailFile(email);
        log.info("Storing email in outbox: '"+ emailFile.getAbsolutePath() + "'");
        OutputStream emailOutputStream = new FileOutputStream(emailFile);
        email.writeTo(emailOutputStream);
        emailOutputStream.close();
    }
    
    private File getEmailFile(Email email) throws MessagingException {
        return new File(storageDir, email.getMessageID() + EMAIL_FILE_EXTENSION);
    }

    public void delete(Email email) throws MessagingException {
        if (!getEmailFile(email).delete())
            log.error("Cannot delete file: '" + getEmailFile(email) + "'");
    }

    @Override
    protected Email createFolderElement(File file) throws Exception {
        FileInputStream inputStream = new FileInputStream(file);
        return new Email(inputStream);
    }
}