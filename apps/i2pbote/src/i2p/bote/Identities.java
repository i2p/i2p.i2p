package i2p.bote;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import net.i2p.data.Hash;
import net.i2p.util.Log;

public class Identities implements Iterable<EmailIdentity> {
    private Log log = new Log(Identities.class);
    private File identitiesFile;
    private List<EmailIdentity> identities;

    public Identities(File identitiesFile) {
        this.identitiesFile = identitiesFile;
        identities = Collections.synchronizedList(new ArrayList<EmailIdentity>());
        
        if (!identitiesFile.exists()) {
            log.debug("Identities file does not exist: <" + identitiesFile.getAbsolutePath() + ">");
            return;
        }
        
        log.debug("Reading identities file: <" + identitiesFile.getAbsolutePath() + ">");
        BufferedReader input = null;
        try {
            input = new BufferedReader(new FileReader(identitiesFile));
            
            while (true) {
                String line = input.readLine();
                if (line == null)   // EOF
                    break;
                
                EmailIdentity identity = parse(line);
                if (identity != null)
                    identities.add(identity);
            }
        } catch (IOException e) {
            log.error("Can't read identities file.", e);
        }
        finally {
            if (input != null)
                try {
                    input.close();
                }
                catch (IOException e) {
                    log.error("Error closing input stream.", e);
                }
        }
    }
 
    private EmailIdentity parse(String emailIdentityString) {
        try {
            String[] fields = emailIdentityString.split("\\t", 4);
            if (fields.length < 2) {
                log.debug("Unparseable email identity: <" + emailIdentityString + ">");
                return null;
            }
            EmailIdentity identity = new EmailIdentity(fields[0]);
            if (fields.length > 1)
                identity.setPublicName(fields[1]);
            if (fields.length > 2)
                identity.setDescription(fields[2]);
            if (fields.length > 3)
                identity.setEmailAddress(fields[3]);
            return identity;
        }
        catch (PatternSyntaxException e) {
            log.debug("Unparseable email identity: <" + emailIdentityString + ">");
            return null;
        }
    }
    
    /**
     * This is the counterpart of the <code>parse</code> method. It encodes a {@link EmailIdentity} into
     * an entry for the identities file.
     * @param identity
     * @return
     */
    private String toFileFormat(EmailIdentity identity) {
        StringBuilder string = new StringBuilder();
        string = string.append(identity.getFullKey());
        string = string.append("\t");
        string = string.append(identity.getPublicName());
        string = string.append("\t");
        if (identity.getDescription() != null)
            string = string.append(identity.getDescription());
        string = string.append("\t");
        if (identity.getEmailAddress() != null)
            string = string.append(identity.getEmailAddress());
        return string.toString();
    }
    
    public void save() throws IOException {
        String newLine = System.getProperty("line.separator");
        try {
            Writer writer = new BufferedWriter(new FileWriter(identitiesFile));
            for (EmailIdentity identity: identities)
                writer.write(toFileFormat(identity) + newLine);
            writer.close();
        }
        catch (IOException e) {
            log.error("Can't save email identities to file <" + identitiesFile.getAbsolutePath() + ">.", e);
            throw e;
        }
    }
    
    public void add(EmailIdentity identity) {
        identities.add(identity);
    }
    
    public void remove(String key) {
        EmailIdentity identity = get(key);
        if (identity != null)
            identities.remove(identity);
    }
    
    public EmailIdentity get(int i) {
        return identities.get(i);
    }

    /**
     * Looks up an {@link EmailIdentity} by its Base64 key. If none is found,
     * <code>null</code> is returned.
     * @param key
     * @return
     */
    public EmailIdentity get(String key) {
        if (key==null || key.isEmpty())
            return null;
        
        for (EmailIdentity identity: identities)
            if (key.equals(identity.getKey()))
                return identity;
        return null;
    }
    
    public Collection<EmailIdentity> getAll() {
        return identities;
    }
    
    public EmailIdentity[] getArray() {
        return identities.toArray(new EmailIdentity[0]);
    }
    
    public int size() {
        return identities.size();
    }
    
    public boolean contains(Hash emailDestination) {
        // TODO
        return true;
    }
    
    @Override
    public Iterator<EmailIdentity> iterator() {
        return identities.iterator();
    }
}