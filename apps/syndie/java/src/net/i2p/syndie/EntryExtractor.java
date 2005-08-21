package net.i2p.syndie;

import java.io.*;
import java.util.*;
import java.util.zip.*;

import net.i2p.data.*;
import net.i2p.syndie.data.*;
import net.i2p.I2PAppContext;

/**
 * To cut down on unnecessary IO/cpu load, extract entries onto the disk for 
 * faster access later.  Individual entries are stored in subdirectories based on
 * their name - $archiveDir/$blogDir/$entryId.snd extracts its files into various
 * files under $cacheDir/$blogDir/$entryId/:
 *  headers.txt: name=value pairs for attributes of the entry container itself
 *  info.txt: name=value pairs for implicit attributes of the container (blog, id, format, size)
 *  entry.sml: raw sml file
 *  attachmentN_data.dat: raw binary data for attachment N
 *  attachmentN_meta.dat: name=value pairs for attributes of attachment N
 * 
 */
public class EntryExtractor {
    private I2PAppContext _context;
    
    static final String HEADERS = "headers.txt";
    static final String META = "meta.txt";
    static final String ENTRY = "entry.sml";
    static final String ATTACHMENT_PREFIX = "attachment";
    static final String ATTACHMENT_DATA_SUFFIX = "_data.dat";
    static final String ATTACHMENT_META_SUFFIX = "_meta.txt";
    static final String ATTACHMENT_DATA_SIZE = "EntryExtractor__dataSize";
    
    public EntryExtractor(I2PAppContext context) {
        _context = context;
    }
    
    public boolean extract(File entryFile, File entryDir, SessionKey entryKey, BlogInfo info) throws IOException {
        EntryContainer entry = new EntryContainer();
        entry.load(new FileInputStream(entryFile));
        boolean ok = entry.verifySignature(_context, info);
        if (!ok) {
            return false;
        } else {
            entry.setCompleteSize((int)entryFile.length());
            if (entryKey != null)
                entry.parseRawData(_context, entryKey);
            else
                entry.parseRawData(_context);
            extract(entry, entryDir);
            return true;
        }
    }
    
    public void extract(EntryContainer entry, File entryDir) throws IOException {
        extractHeaders(entry, entryDir);
        extractMeta(entry, entryDir);
        extractEntry(entry, entryDir);
        Attachment attachments[] = entry.getAttachments();
        if (attachments != null) {
            for (int i = 0; i < attachments.length; i++) {
                extractAttachmentData(i, attachments[i], entryDir);
                extractAttachmentMetadata(i, attachments[i], entryDir);
            }
        }
    }
    private void extractHeaders(EntryContainer entry, File entryDir) throws IOException {
        FileWriter out = null;
        try {
            out = new FileWriter(new File(entryDir, HEADERS));
            Map headers = entry.getHeaders();
            for (Iterator iter = headers.keySet().iterator(); iter.hasNext(); ) {
                String k = (String)iter.next();
                String v = (String)headers.get(k);
                out.write(k.trim() + '=' + v.trim() + '\n');
            }
        } finally {
            out.close();
        }
    }
    private void extractMeta(EntryContainer entry, File entryDir) throws IOException {
        FileWriter out = null;
        try {
            out = new FileWriter(new File(entryDir, META));
            out.write("format=" + entry.getFormat() + '\n');
            out.write("size=" + entry.getCompleteSize() + '\n');
            out.write("blog=" + entry.getURI().getKeyHash().toBase64() + '\n');
            out.write("entry=" + entry.getURI().getEntryId() + '\n');
        } finally {
            out.close();
        }
    }
    private void extractEntry(EntryContainer entry, File entryDir) throws IOException {
        FileWriter out = null;
        try {
            out = new FileWriter(new File(entryDir, ENTRY));
            out.write(entry.getEntry().getText());
        } finally {
            out.close();
        }
    }
    private void extractAttachmentData(int num, Attachment attachment, File entryDir) throws IOException {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(entryDir, ATTACHMENT_PREFIX + num + ATTACHMENT_DATA_SUFFIX));
            //out.write(attachment.getData());
            InputStream data = attachment.getDataStream();
            byte buf[] = new byte[1024];
            int read = 0;
            while ( (read = data.read(buf)) != -1) 
                out.write(buf, 0, read);
            data.close();
        } finally {
            out.close();
        }
    }
    private void extractAttachmentMetadata(int num, Attachment attachment, File entryDir) throws IOException {
        FileWriter out = null;
        try {
            out = new FileWriter(new File(entryDir, ATTACHMENT_PREFIX + num + ATTACHMENT_META_SUFFIX));
            Map meta = attachment.getMeta();
            for (Iterator iter = meta.keySet().iterator(); iter.hasNext(); ) {
                String k = (String)iter.next();
                String v = (String)meta.get(k);
                out.write(k + '=' + v + '\n');
            }
            out.write(ATTACHMENT_DATA_SIZE + '=' + attachment.getDataLength());
        } finally {
            out.close();
        }
    }
}
