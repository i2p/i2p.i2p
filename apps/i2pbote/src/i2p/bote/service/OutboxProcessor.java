package i2p.bote.service;

import i2p.bote.Configuration;
import i2p.bote.EmailDestination;
import i2p.bote.folder.Outbox;
import i2p.bote.network.EmailAddressResolver;
import i2p.bote.network.PeerManager;
import i2p.bote.packet.Email;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;

import net.i2p.client.I2PSession;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

import com.nettgryppa.security.HashCash;

/**
 * A background thread that checks the outbox for emails and sends them to the I2P network.
 *
 * @author HungryHobo@mail.i2p
 */
public class OutboxProcessor extends I2PAppThread {
	private static final int PAUSE = 10;   // The wait time, in minutes, before processing the folder again. Can be interrupted from the outside.
	
	private Log log = new Log(OutboxProcessor.class);
	private Outbox outbox;
	private Configuration configuration;
	private EmailAddressResolver emailAddressResolver;
	private Map<Address, String> statusMap;
	private CountDownLatch checkForEmailSignal;
	
	public OutboxProcessor(I2PSession i2pSession, Outbox outbox, Configuration configuration, PeerManager peerManager) {
		super("OutboxProcessor");
		this.outbox = outbox;
		this.configuration = configuration;
		statusMap = new ConcurrentHashMap<Address, String>();
		emailAddressResolver = new EmailAddressResolver();
	}
	
	@Override
	public void run() {
		while (true) {
            synchronized(this) {
                checkForEmailSignal = new CountDownLatch(1);
            }
            
			log.info("Processing outgoing emails in directory '" + outbox.getStorageDirectory() + "'.");
			for (Email email: outbox) {
			    log.info("Processing outbox file: '" + email.getFile() + "'.");
				try {
					sendEmail(email);
				} catch (Exception e) {
				    log.error("Error sending email.", e);
				}
			}
			
			try {
	            checkForEmailSignal.await(PAUSE, TimeUnit.MINUTES);
			} catch (InterruptedException e) {
			    log.error("OutboxProcessor received an InterruptedException.", e);
			}
		}
	}
	
	/**
	 * Tells the <code>OutboxProcessor</code> to check for new outgoing emails immediately.
	 */
	public void checkForEmail() {
	    checkForEmailSignal.countDown();
	}
	
	/**
	 * Send an {@link Email} to all recipients specified in the header.
	 * @param email
	 * @throws MessagingException
	 * @throws IOException
	 */
	private void sendEmail(Email email) throws MessagingException, IOException {
		email.saveChanges();   // this updates the headers
		email.scrubHeaders();   // TODO if the MimeMessage implementation doesn't remove BCC fields, move this line to EmailSendTask.sendIndividual(), after addHashCash
		
		for (Address recipient: email.getAllRecipients())
			// only handle email addresses, report an error for news addresses
			if (recipient instanceof InternetAddress) {
				String recipientAddress = ((InternetAddress)recipient).getAddress();
				sendToOne(recipientAddress, email);
			}
			else
			    log.error("Illegal recipient type: " + recipient.getType());
	}

	/**
     * Send an {@link Email} to one recipient.
	 * @param address
	 * @param email
	 */
	private void sendToOne(String address, Email email) {
		String logSuffix = null;   // only used for logging
		try {
			addHashCash(email);
			logSuffix = "Recipient = '" + address + "' Message ID = '" + email.getMessageID() + "'";
			EmailDestination emailDestination = emailAddressResolver.getDestination(address);
		}
		catch (Exception e) {
		    log.error("Error trying to send email. " + logSuffix);
			outbox.updateStatus(email, new int[] { 1 }, "Email sent to recipient: " + address);
			return;
		}
		
/*		int[] numForwarded = new int[fragments.size()];
		for (int fragmentIndex=0; fragmentIndex<fragments.size(); fragmentIndex++)
		    for (int i=0; i<configuration.getRedundancy(); i++) {
		        Destination peer = peerManager.getRandomPeer();
			    Authorizer.Response relaySendResult = sendToHost(fragments.get(fragmentIndex), peer);
				if (Authorizer.Response.ACCEPTED.equals(relaySendResult))
					numForwarded[fragmentIndex]++;
				int redundancy = configuration.getRedundancy();
				
				boolean done = true;
				for (int j=0; j<numForwarded.length; j++)
					if (numForwarded[j] < redundancy)
						done = false;
				if (done) {
					outbox.updateStatus(email, numForwarded, "Email sent to " + redundancy + " relays, plus kept on localhost.");
					log.info("Email submitted to relays. " + logSuffix);
					return;
				}
			}
			log.info("Relaying not yet successful. " + logSuffix);
			outbox.updateStatus(email, numForwarded, "Relaying failed or succeeded partially, will try again soon.");
		}*/
	}

	private void addHashCash(Email email) throws NoSuchAlgorithmException, MessagingException {
		email.setHeader("X-HashCash", HashCash.mintCash("", configuration.getHashCashStrength()).toString());
	}
	
	public Map<Address, String> getStatus() {
		return statusMap;
	}
	
	public void shutDown() {
	    // TODO
	}
}