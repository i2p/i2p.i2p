package i2p.susi.webmail;

import java.io.Serializable;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * Sorters
 * 
 * @since 0.9.34 pulled out of WebMail
 */
class Sorters {

	/**
	 * Base for the various sorters
	 * 
	 * @since 0.9.13
	 */
	private abstract static class SorterBase implements Comparator<String>, Serializable {
		private final MailCache mailCache;
		
		/**
		 * Set MailCache object, where to get Mails from
		 * @param mailCache
		 */
		protected SorterBase( MailCache mailCache )
		{
			this.mailCache = mailCache;
		}
		
		/**
		 *  Gets mail from the cache, checks for null, then compares
		 */
		public int compare(String arg0, String arg1) {
			Mail a = mailCache.getMail( arg0, MailCache.FetchMode.CACHE_ONLY );
			Mail b = mailCache.getMail( arg1, MailCache.FetchMode.CACHE_ONLY );
			if (a == null)
				return (b == null) ? 0 : 1;
			if (b == null)
				return -1;
			int rv = compare(a, b);
			if (rv != 0)
				return rv;
			return fallbackCompare(a, b);
		}		

		/**
		 * @param a non-null
		 * @param b non-null
		 */
		protected abstract int compare(Mail a, Mail b);

		/**
		 * @param a non-null
		 * @param b non-null
		 */
		private int fallbackCompare(Mail a, Mail b) {
			return DateSorter.scompare(a, b);
		}
	}

	/**
	 * sorts Mail objects by sender field
	 * 
	 * @author susi
	 */
	public static class SenderSorter extends SorterBase {

		private final Comparator<Object> collator = Collator.getInstance();

		public SenderSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			String as = a.sender.replace("\"", "").replace("<", "").replace(">", "");
			String bs = b.sender.replace("\"", "").replace("<", "").replace(">", "");
			return collator.compare(as, bs);
		}		
	}

	/**
	 * sorts Mail objects by subject field
	 * @author susi
	 */
	public static class SubjectSorter extends SorterBase {
		// tagged in WebMail
		private static final String xre = Messages.getString("Re:").toLowerCase(Locale.US);
		private static final String xfwd = Messages.getString("Fwd:").toLowerCase(Locale.US);
		private final Comparator<Object> collator = Collator.getInstance();

		public SubjectSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			String as = a.subject;
			String bs = b.subject;
			String aslc = as.toLowerCase(Locale.US);
			String bslc = bs.toLowerCase(Locale.US);
			if (aslc.startsWith("re:") || aslc.startsWith("fw:")) {
				as = as.substring(3).trim();
			} else if (aslc.startsWith("fwd:")) {
				as = as.substring(4).trim();
			} else {
				if (aslc.startsWith(xre)) {
					as = as.substring(xre.length()).trim();
				} else {
					if (aslc.startsWith(xfwd))
						as = as.substring(xfwd.length()).trim();
				}
			}
			if (bslc.startsWith("re:") || bslc.startsWith("fw:")) {
				bs = bs.substring(3).trim();
			} else if (bslc.startsWith("fwd:")) {
				bs = bs.substring(4).trim();
			} else {
				if (bslc.startsWith(xre)) {
					bs = bs.substring(xre.length()).trim();
				} else {
					if (bslc.startsWith(xfwd))
						bs = bs.substring(xfwd.length()).trim();
				}
			}
			return collator.compare(as, bs);
		}		
	}

	/**
	 * sorts Mail objects by date field
	 * @author susi
	 */
	public static class DateSorter extends SorterBase {

		public DateSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			return scompare(a, b);
		}

		/**
		 * Use as fallback in other sorters
		 * @param a non-null
		 * @param b non-null
		 */
		public static int scompare(Mail a, Mail b) {
			return a.date != null ? ( b.date != null ? a.date.compareTo( b.date ) : -1 ) : ( b.date != null ? 1 : 0 );
		}		
	}

	/**
	 * sorts Mail objects by message size
	 * @author susi
	 */
	public static class SizeSorter extends SorterBase {

		public SizeSorter( MailCache mailCache )
		{
			super(mailCache);
		}
		
		protected int compare(Mail a, Mail b) {
			long as = a.getSize();
			long bs = b.getSize();
			if (as > bs)
				return 1;
			if (as < bs)
				return -1;
			return 0;
		}		
	}
}
