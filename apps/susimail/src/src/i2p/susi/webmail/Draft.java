package i2p.susi.webmail;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.i2p.data.Base64;
import net.i2p.data.DataHelper;

import i2p.susi.util.Buffer;
import i2p.susi.webmail.encoding.Encoding;
import i2p.susi.webmail.encoding.EncodingException;
import i2p.susi.webmail.encoding.EncodingFactory;

/**
 * Holds a draft message and reference to attachments, if any
 * 
 * Differences from Mail:
 * - Never multipart, body is always text/plain UTF-8
 * - Attachments are just headers containing name, type, encoding, and path to file
 * - Bcc is a header
 * 
 * @since 0.9.35
 */
class Draft extends Mail {

	private final List<Attachment> attachments;
	String[] bcc;        // addresses only, enclosed by <>
	private static final String HDR_ATTACH = "X-I2P-Attachment: ";
	public static final String HDR_BCC = "Bcc: ";
	
	public Draft(String uidl) {
		super(uidl);
		attachments = new ArrayList<Attachment>(4);
	}

	/**
	 *  Overridden to process attachment and Bcc headers
	 */
	@Override
	public synchronized void setBody(Buffer rb) {
		super.setBody(rb);
		MailPart part = getPart();
		if (part != null) {
			String[] hdrs = part.headerLines;
			for (int i = 0; i < hdrs.length; i++) {
				String hdr = hdrs[i];
				if (hdr.startsWith(HDR_BCC)) {
					ArrayList<String> list = new ArrayList<String>();
					getRecipientsFromList(list, hdr.substring(HDR_BCC.length()).trim(), true);
					if (list.isEmpty()) {
						// don't set
					} else if (bcc == null) {
						bcc = list.toArray(new String[list.size()]);
					} else {	
						// add to the array, shouldn't happen
						for (int j = 0; j < bcc.length; j++) {
							list.add(j, bcc[i]);
						}
						bcc = list.toArray(new String[list.size()]);
					}
				}
				if (!hdr.startsWith(HDR_ATTACH))
					break;
				String[] flds = DataHelper.split(hdr.substring(HDR_ATTACH.length()), ",", 4);
				if (flds.length != 4)
					continue;
				byte[] b = Base64.decode(flds[0]);
				if (b == null)
					continue;
				String name = DataHelper.getUTF8(b);
				String type = flds[1];
				String enc = flds[2];
				b = Base64.decode(flds[0]);
				if (b == null)
					continue;
				String path = DataHelper.getUTF8(b);
				attachments.add(new Attachment(name, type, enc, new File(path)));
			}
		}
	}

	@Override
	public synchronized boolean hasAttachment() {
		return !attachments.isEmpty();
	}

	/** @return may be null */
	public synchronized String[] getBcc() {
		return bcc;
	}

	/** @return non-null, not a copy */
	public synchronized List<Attachment> getAttachments() {
		return attachments;
	}

	public synchronized int addAttachment(Attachment a) {
		int rv = attachments.indexOf(a);
		if (rv >= 0)
			return rv;
		rv = attachments.size();
		attachments.add(a);
		return rv;
	}

	public synchronized void removeAttachment(int index) {
		if (index >= 0 && index < attachments.size()) {
			Attachment a = attachments.get(index);
			a.deleteData();
			attachments.remove(index);
		}
	}

	public synchronized void clearAttachments() {
		for (Attachment a : attachments) {
			a.deleteData();
		}
		attachments.clear();
	}

	public synchronized StringBuilder encodeAttachments() {
		StringBuilder buf = new StringBuilder(256 * attachments.size());
		if (attachments.isEmpty())
			return buf;
		for (int i = 0; i < attachments.size(); i++) {
			Attachment a = attachments.get(i);
			buf.append(HDR_ATTACH);
			buf.append(Base64.encode(a.getFileName())).append(',');
			buf.append(a.getContentType()).append(',');
			buf.append(a.getTransferEncoding()).append(',');
			buf.append(Base64.encode(a.getPath())).append("\r\n");
		}
		return buf;
	}
}
