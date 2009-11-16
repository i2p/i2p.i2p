package i2p.bote;

import java.io.File;
import java.io.IOException;
import java.util.Properties;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.util.Log;

public class Configuration extends Properties {
    private static final long serialVersionUID = -6318245413106186095L;
	private static final String I2P_BOTE_SUBDIR = ".i2pbote";       // relative to the I2P app dir
	private static final String CONFIG_FILE_NAME = "i2pbote.config";
    private static final String DEST_KEY_FILE_NAME = "local_dest.key";
    private static final String PEER_FILE_NAME = "peers.txt";
    private static final String IDENTITIES_FILE_NAME = "identities.txt";
    private static final String OUTBOX_DIR = "outbox";              // relative to I2P_BOTE_SUBDIR
    private static final String OUTBOX_SUBDIR_LOCAL = "local";      // relative to OUTBOX_DIR
    private static final String OUTBOX_SUBDIR_RELAY = "relay";      // relative to OUTBOX_DIR
    private static final String INCOMPLETE_SUBDIR = "incomplete";   // relative to I2P_BOTE_SUBDIR
    private static final String EMAIL_DHT_SUBDIR = "dht_email_pkt";    // relative to I2P_BOTE_SUBDIR
    private static final String INDEX_PACKET_DHT_SUBDIR = "dht_index_pkt";    // relative to I2P_BOTE_SUBDIR
    private static final String INBOX_SUBDIR = "inbox";             // relative to I2P_BOTE_SUBDIR
	
	// Parameter names in the config file
	private static final String PARAMETER_REDUNDANCY = "redundancy";
	private static final String PARAMETER_STORAGE_SPACE_INBOX = "storageSpaceInbox";
	private static final String PARAMETER_STORAGE_SPACE_RELAY = "storageSpaceRelay";
	private static final String PARAMETER_STORAGE_TIME = "storageTime";
	private static final String PARAMETER_MAX_FRAGMENT_SIZE = "maxFragmentSize";
	private static final String PARAMETER_HASHCASH_STRENGTH = "hashCashStrength";
	private static final String PARAMETER_SMTP_PORT = "smtpPort";
    private static final String PARAMETER_POP3_PORT = "pop3Port";
    
	// Defaults for each parameter
	private static final int DEFAULT_REDUNDANCY = 2;
	private static final int DEFAULT_STORAGE_SPACE_INBOX = 1024 * 1024 * 1024;
	private static final int DEFAULT_STORAGE_SPACE_RELAY = 100 * 1024 * 1024;
	private static final int DEFAULT_STORAGE_TIME = 31;   // in days
	private static final int DEFAULT_MAX_FRAGMENT_SIZE = 10 * 1024 * 1024;   // the maximum size one email fragment can be, in bytes
	private static final int DEFAULT_HASHCASH_STRENGTH = 10;
    private static final int DEFAULT_SMTP_PORT = 7661;
    private static final int DEFAULT_POP3_PORT = 7662;
	
	private Log log = new Log(Configuration.class);
	private File i2pBoteDir;
	
	/**
	 * Reads configuration settings from the <code>I2P_BOTE_SUBDIR</code> subdirectory under
	 * the I2P application directory. The I2P application directory can be changed via the
	 * <code>i2p.dir.app</code> system property.
	 * 
	 * Logging is done through the I2P logger. I2P reads the log configuration from the
	 * <code>logger.config</code> file whose location is determined by the
	 * <code>i2p.dir.config</code> system property.
	 * @return
	 */
	public Configuration() {
		// get the I2PBote directory and make sure it exists
		i2pBoteDir = getI2PBoteDirectory();
		if (!i2pBoteDir.exists())
		    i2pBoteDir.mkdirs();

		// read the configuration file
        File configFile = new File(i2pBoteDir, CONFIG_FILE_NAME);
        boolean configurationLoaded = false;
        if (configFile.exists()) {
			log.debug("Loading config file <" + configFile.getAbsolutePath() + ">");
			
            try {
                DataHelper.loadProps(this, configFile);
                configurationLoaded = true;
            } catch (IOException e) {
            	log.error("Error loading configuration file <" + configFile.getAbsolutePath() + ">", e);
            }
        }
        if (!configurationLoaded)
            log.info("Can't read configuration file <" + configFile.getAbsolutePath() + ">, using default settings.");
	}

	public File getDestinationKeyFile() {
	    return new File(i2pBoteDir, DEST_KEY_FILE_NAME);
	}
	
	public File getPeerFile() {
	    return new File(i2pBoteDir, PEER_FILE_NAME);
	}
	
    public File getIdentitiesFile() {
        return new File(i2pBoteDir, IDENTITIES_FILE_NAME);
    }
    
	public File getLocalOutboxDir() {
	    return new File(getOutboxBaseDir(), OUTBOX_SUBDIR_LOCAL);	    
	}
	
    public File getRelayOutboxDir() {
        return new File(getOutboxBaseDir(), OUTBOX_SUBDIR_RELAY);       
    }
    
	private File getOutboxBaseDir() {
	    return new File(i2pBoteDir, OUTBOX_DIR);
	}
	
    public File getInboxDir() {
        return new File(i2pBoteDir, INBOX_SUBDIR);       
    }
    
    public File getIncompleteDir() {
        return new File(i2pBoteDir, INCOMPLETE_SUBDIR);       
    }
    
    public File getEmailDhtStorageDir() {
        return new File(i2pBoteDir, EMAIL_DHT_SUBDIR);       
    }
    
    public File getIndexPacketDhtStorageDir() {
        return new File(i2pBoteDir, INDEX_PACKET_DHT_SUBDIR);
    }
    
    private static File getI2PBoteDirectory() {
        // the parent directory of the I2PBote directory ($HOME or the value of the i2p.dir.app property)
        File i2pAppDir = I2PAppContext.getGlobalContext().getAppDir();
        
        return new File(i2pAppDir, I2P_BOTE_SUBDIR);
    }
    
	/**
	 * Save the configuration
	 * @param configFile
	 * @throws IOException
	 */
	public void saveToFile(File configFile) throws IOException {
		log.debug("Saving config file <" + configFile.getAbsolutePath() + ">");
		DataHelper.storeProps(this, configFile);
	}

	/**
	 * Returns the number of relays to use for sending and receiving email. Zero is a legal value.
	 * @return
	 */
	public int getRedundancy() {
		return getIntParameter(PARAMETER_REDUNDANCY, DEFAULT_REDUNDANCY);
	}

	/**
	 * Returns the maximum size (in bytes) the inbox can take up.
	 * @return
	 */
	public int getStorageSpaceInbox() {
		return getIntParameter(PARAMETER_STORAGE_SPACE_INBOX, DEFAULT_STORAGE_SPACE_INBOX);
	}
	
	/**
	 * Returns the maximum size (in bytes) all messages stored for relaying can take up.
	 * @return
	 */
	public int getStorageSpaceRelay() {
		return getIntParameter(PARAMETER_STORAGE_SPACE_RELAY, DEFAULT_STORAGE_SPACE_RELAY);
	}
	
	/**
	 * Returns the time (in milliseconds) after which an email is deleted from the outbox if it cannot be sent / relayed.
	 * @return
	 */
	public long getStorageTime() {
		return 24L * 3600 * 1000 * getIntParameter(PARAMETER_STORAGE_TIME, DEFAULT_STORAGE_TIME);
	}

	public int getMaxFragmentSize() {
		return getIntParameter(PARAMETER_MAX_FRAGMENT_SIZE, DEFAULT_MAX_FRAGMENT_SIZE);
	}
	
	public int getHashCashStrength() {
		return getIntParameter(PARAMETER_HASHCASH_STRENGTH, DEFAULT_HASHCASH_STRENGTH);
	}
	
	private int getIntParameter(String parameterName, int defaultValue) {
		String stringValue = getProperty(parameterName);
		if (stringValue == null)
			return defaultValue;
		else
			try {
				return new Integer(getProperty(parameterName));
			}
			catch (NumberFormatException e) {
				log.warn("Can't convert value <" + stringValue + "> for parameter <" + parameterName + "> to int, using default.");
				return defaultValue;
			}
	}
}