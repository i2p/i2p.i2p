package i2p.susi.webmail.pop3;

import i2p.susi.webmail.WebMail;
import i2p.susi.util.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.ConcurrentHashSet;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;


/**
 *  Check for new mail periodically
 *
 *  @since 0.9.13
 */
class BackgroundChecker {

	private final POP3MailBox mailbox;
	private final Set<String> toDelete;
	private final SimpleTimer2.TimedEvent timer;
	private volatile boolean isChecking;
	private volatile boolean isDead;
	private final Log _log;

	private static final int DEFAULT_CHECK_MINUTES = 3*60;
	private static final int MIN_CHECK_MINUTES = 15;
	// short for testing
	//private static final long MIN_IDLE = 10*60*1000;
	private static final long MIN_IDLE = 30*60*1000;
	// short for testing
	//private static final long MIN_SINCE = 10*60*1000;
	private static final long MIN_SINCE = 60*60*1000;

	public BackgroundChecker(POP3MailBox mailbox) {
		this.mailbox = mailbox;
		toDelete = new ConcurrentHashSet<String>();
		timer = new Checker();
		_log = I2PAppContext.getGlobalContext().logManager().getLog(BackgroundChecker.class);
	}

	public Collection<String> getQueued() {
		List<String> rv = new ArrayList<String>(toDelete);
		return rv;
	}

	public void cancel() {
		isDead = true;
		timer.cancel();
	}

	private long getCheckTime() {
		int minutes = DEFAULT_CHECK_MINUTES;
		String con = Config.getProperty(WebMail.CONFIG_CHECK_MINUTES);
		if (con != null) {
			try {
				int mins = Integer.parseInt(con);
				// allow shorter for testing
				if (mins < MIN_CHECK_MINUTES && !_log.shouldDebug())
					mins = MIN_CHECK_MINUTES;
				minutes = mins;
			} catch (NumberFormatException nfe) {}
		}
		return minutes * 60 * 1000L;
	}

	private class Checker extends SimpleTimer2.TimedEvent {

		public Checker() {
			super(I2PAppContext.getGlobalContext().simpleTimer2(), getCheckTime());
		}

	        public void timeReached() {
			if (isDead)
				return;
			if (!mailbox.isConnected() && !isChecking) {
				long idle = System.currentTimeMillis() - mailbox.getLastActivity();
				long last = System.currentTimeMillis() - mailbox.getLastChecked();
				if (idle >= MIN_IDLE && last >= MIN_SINCE) {
					if (_log.shouldDebug()) _log.debug("Threading check for mail after " +
							idle + " ms idle and " + last + " since last check");
					Thread t = new Getter();
					isChecking = true;
					t.start();
				} else {
					if (_log.shouldDebug()) _log.debug("Not checking after " +
							idle + " ms idle and " + last + " since last check");
				}
			} else {
				if (_log.shouldDebug()) _log.debug("Not checking, still connected");
			}
			schedule(getCheckTime());
		}
	}

	private class Getter extends I2PAppThread {

		public Getter() {
			super("Susimail-Getter");
		}

	        public void run() {
			try {
				if (mailbox.blockingConnectToServer()) {
					int found = mailbox.getNumMails();
					if (found > 0) {
						if (_log.shouldDebug()) _log.debug("Found " + found + " mails, calling listener");
						// may not really be new
						mailbox.foundNewMail(true);
					}
				}
			} finally {		
				isChecking = false;
				if (!isDead)
					timer.schedule(getCheckTime());
			}		
		}
	}
}
