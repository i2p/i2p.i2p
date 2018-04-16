package i2p.susi.webmail.pop3;

import i2p.susi.webmail.WebMail;
import i2p.susi.util.Config;

import net.i2p.I2PAppContext;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;


/**
 *  Close the POP3 connection after a certain idle time
 *
 *  @since 0.9.13
 */
class IdleCloser {

	private final POP3MailBox mailbox;
	private final SimpleTimer2.TimedEvent timer;
	private final Log _log;
	private volatile boolean isClosing;
	private volatile boolean isDead;

	private static final long CHECK_TIME = 30*1000;
	// POP3 RFC 1939 server minimum idle timeout is 10 minutes
	// pop3.postman.i2p timeout is 5 minutes
	// We want to be less than that.
	private static final int DEFAULT_IDLE_SECONDS = 4*60;
	private static final int MIN_IDLE_CONFIG = 60;

	public IdleCloser(POP3MailBox mailbox) {
		this.mailbox = mailbox;
		timer = new Checker();
		_log = I2PAppContext.getGlobalContext().logManager().getLog(IdleCloser.class);
	}

	public void cancel() {
		isDead = true;
		timer.cancel();
	}

	private static long getMaxIdle() {
		int seconds = DEFAULT_IDLE_SECONDS;
		String con = Config.getProperty(WebMail.CONFIG_IDLE_SECONDS);
		if (con != null) {
			try {
				int secs = Integer.parseInt(con);
				if (secs < MIN_IDLE_CONFIG)
					secs = MIN_IDLE_CONFIG;
				seconds = secs;
			} catch (NumberFormatException nfe) {}
		}
		return seconds * 1000L;
	}

	private class Checker extends SimpleTimer2.TimedEvent {

		public Checker() {
			super(I2PAppContext.getGlobalContext().simpleTimer2(), getMaxIdle() + 5*1000);
		}

	        public void timeReached() {
			if (isDead)
				return;
			// unsynchronized here, synch in thread only
			if (!mailbox.isConnected())
				return;
			if (!isClosing) {
				long config = getMaxIdle();
				long idle = System.currentTimeMillis() - mailbox.getLastActivity();
				long remaining = config - idle;
				Log log = IdleCloser.this._log;
				if (remaining <= 0) {
					if (log.shouldDebug()) log.debug("Threading close after " +
							idle + " ms idle");
					Thread t = new Closer();
					isClosing = true;
					t.start();
				} else {
					if (log.shouldDebug()) log.debug("Not closing after " +
							idle + " ms idle");
					schedule(remaining + 5000);
				}
			}
		}
	}

	private class Closer extends I2PAppThread {

		public Closer() {
			super("Susimail-Closer");
		}

	        public void run() {
			try {
				synchronized (mailbox.getLock()) {
					if (!mailbox.isConnected())
						return;
					long config = getMaxIdle();
					long idle = System.currentTimeMillis() - mailbox.getLastActivity();
					long remaining = config - idle;
					if (remaining <= 0) {
						// If we have items to delete, wait for the response code,
						// otherwise the DelayedDeleter thread will have to run later.
						// Since we are threaded we can do that here.
						boolean shouldWait = mailbox.hasQueuedDeletions();
						mailbox.close(shouldWait);
						isDead = true;
					} else {
						timer.schedule(remaining + 5000);
					}
				}
			} finally {		
				isClosing = false;
			}		
		}
	}
}
