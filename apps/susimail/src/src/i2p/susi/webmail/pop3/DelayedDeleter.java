package i2p.susi.webmail.pop3;

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
 *  Queue UIDLs for later deletion.
 *  We send deletions at close time but we don't wait around
 *  for the answer. Also, the user may delete mails when offline.
 *  So we queue them here and reconnect to delete.
 *
 *  @since 0.9.13
 */
class DelayedDeleter {

	private final POP3MailBox mailbox;
	private final Set<String> toDelete;
	private final SimpleTimer2.TimedEvent timer;
	private final Log _log;
	private volatile boolean isDeleting;
	private volatile boolean isDead;

	private static final long CHECK_TIME = 16*60*1000;
	private static final long MIN_IDLE = 60*60*1000;

	public DelayedDeleter(POP3MailBox mailbox) {
		this.mailbox = mailbox;
		toDelete = new ConcurrentHashSet<String>();
		timer = new Checker();
		_log = I2PAppContext.getGlobalContext().logManager().getLog(DelayedDeleter.class);
	}

	public void queueDelete(String uidl) {
		toDelete.add(uidl);
	}

	public void removeQueued(String uidl) {
		toDelete.remove(uidl);
	}

	public Collection<String> getQueued() {
		List<String> rv = new ArrayList<String>(toDelete);
		return rv;
	}

	public void cancel() {
		isDead = true;
		timer.cancel();
	}

	private class Checker extends SimpleTimer2.TimedEvent {

		public Checker() {
			super(I2PAppContext.getGlobalContext().simpleTimer2(), CHECK_TIME + 5*1000);
		}

	        public void timeReached() {
			if (isDead)
				return;
			if (!toDelete.isEmpty() && !isDeleting) {
				long idle = System.currentTimeMillis() - mailbox.getLastActivity();
				if (idle >= MIN_IDLE) {
					if (_log.shouldDebug()) _log.debug("Threading delayed delete for " + toDelete.size() +
							" mails after " + idle + " ms idle");
					Thread t = new Deleter();
					isDeleting = true;
					t.start();
				} else {
					if (_log.shouldDebug()) _log.debug("Not deleting " + toDelete.size() + ", only idle " + idle);
				}
			} else {
				if (_log.shouldDebug()) _log.debug("Nothing to delete");
			}
			schedule(CHECK_TIME);
		}
	}

	private class Deleter extends I2PAppThread {

		public Deleter() {
			super("Susimail-Delete");
		}

	        public void run() {
			try {
				int origSize = toDelete.size();
				mailbox.deletePending(false);
				int delSize = origSize - toDelete.size();
				if (_log.shouldDebug()) _log.debug("Deleted " + delSize + " of " + origSize + " mails");
			} finally {		
				isDeleting = false;
				if (!isDead)
					timer.schedule(CHECK_TIME);
			}		
		}
	}
}
