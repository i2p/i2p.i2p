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
	private String[] bcc;        // addresses only, enclosed by <>
	private long size;
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
		attachments.clear();
		bcc = null;
		super.setBody(rb);
		size = rb.getLength();
		MailPart part = getPart();
		if (part != null) {
			String[] hdrs = part.headerLines;
			for (int i = 0; i < hdrs.length; i++) {
				String hdr = hdrs[i];
				if (hdr.regionMatches(true, 0, HDR_BCC, 0, HDR_BCC.length())) {
					ArrayList<String> list = new ArrayList<String>();
					getRecipientsFromList(list, hdr.substring(HDR_BCC.length()).trim(), true);
					if (list.isEmpty()) {
						// don't set
					} else if (bcc == null) {
						bcc = list.toArray(new String[list.size()]);
					} else {	
						// add to the array, shouldn't happen
						for (int j = 0; j < bcc.length; j++) {
							list.add(j, bcc[j]);
						}
						bcc = list.toArray(new String[list.size()]);
					}
				}
				if (!hdr.regionMatches(true, 0, HDR_ATTACH, 0, HDR_ATTACH.length()))
					continue;
				String[] flds = DataHelper.split(hdr.substring(HDR_ATTACH.length()), ",", 4);
				if (flds.length != 4)
					continue;
				byte[] b = Base64.decode(flds[0]);
				if (b == null)
					continue;
				String name = DataHelper.getUTF8(b);
				String type = flds[1];
				String enc = flds[2];
                if (!type.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+(?:;[^\\r\\n,]*)?") ||
                    !(enc.equals("base64") || enc.equals("quoted-printable") || enc.equals("7bit") || enc.equals("8bit") || enc.equals("binary"))) continue;
				b = Base64.decode(flds[3]);
				if (b == null)
					continue;
				String path = DataHelper.getUTF8(b);
				MailVault vault = rb instanceof VaultFileBuffer ? ((VaultFileBuffer) rb).vault : null;
				File file = new File(path);
				if (vault != null) {
					java.nio.file.Path allowed = vault.directory.resolve(WebMail.DIR_DRAFTS).resolve(PersistentMailCache.DIR_ATTACHMENTS);
					if (!file.toPath().toAbsolutePath().normalize().startsWith(allowed) || java.nio.file.Files.isSymbolicLink(file.toPath()))
						continue;
				}
				Attachment a = new Attachment(name, type, enc, file, vault);
				size += a.getSize();
				attachments.add(a);
			}
		}
	}

	/**
	 * Includes size of attachments
	 *
	 * @since 0.9.62
	 */
	@Override
	public synchronized long getSize() {
		return size;
	}

	/**
	 * @since 0.9.62
	 */
	@Override
	public synchronized void setSize(long size) {}

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
		size += a.getSize();
		return rv;
	}

	public synchronized void removeAttachment(int index) {
		if (index >= 0 && index < attachments.size()) {
			Attachment a = attachments.get(index);
			size -= a.getSize();
			a.deleteData();
			attachments.remove(index);
		}
	}

	public synchronized void clearAttachments() {
		for (Attachment a : attachments) {
			size -= a.getSize();
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
