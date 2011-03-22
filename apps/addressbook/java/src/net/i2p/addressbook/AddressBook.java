/*
 * Copyright (c) 2004 Ragnarok
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package net.i2p.addressbook;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.util.EepGet;
import net.i2p.util.SecureFile;

/**
 * An address book for storing human readable names mapped to base64 i2p
 * destinations. AddressBooks can be created from local and remote files, merged
 * together, and written out to local files.
 * 
 * @author Ragnarok
 *  
 */
class AddressBook {

    private final String location;
    /** either addresses or subFile will be non-null, but not both */
    private final Map<String, String> addresses;
    private final File subFile;
    private boolean modified;
    private static final boolean DEBUG = true;

    /**
     * Construct an AddressBook from the contents of the Map addresses.
     * 
     * @param addresses
     *            A Map containing human readable addresses as keys, mapped to
     *            base64 i2p destinations.
     */
    public AddressBook(Map<String, String> addresses) {
        this.addresses = addresses;
        this.subFile = null;
        this.location = null;
    }

    /*
     * Construct an AddressBook from the contents of the file at url. If the
     * remote file cannot be read, construct an empty AddressBook
     * 
     * @param url
     *            A URL pointing at a file with lines in the format "key=value",
     *            where key is a human readable name, and value is a base64 i2p
     *            destination.
     */
/* unused
    public AddressBook(String url, String proxyHost, int proxyPort) {
        this.location = url;
        EepGet get = new EepGet(I2PAppContext.getGlobalContext(), true,
                proxyHost, proxyPort, 0, "addressbook.tmp", url, true, 
                null);
        get.fetch();
        try {
            this.addresses = ConfigParser.parse(new File("addressbook.tmp"));
        } catch (IOException exp) {
            this.addresses = new HashMap();
        }
        new File("addressbook.tmp").delete();
    }
*/
    static final long MAX_SUB_SIZE = 3 * 1024 * 1024l; //about 5,000 hosts

    /**
     * Construct an AddressBook from the Subscription subscription. If the
     * address book at subscription has not changed since the last time it was
     * read or cannot be read, return an empty AddressBook.
     * Set a maximum size of the remote book to make it a little harder for a malicious book-sender.
     * 
     * Yes, the EepGet fetch() is done in this constructor.
     * 
     * This stores the subscription in a temporary file and does not read the whole thing into memory.
     * An AddressBook created with this constructor may not be modified or written using write().
     * It may be a merge source (an parameter for another AddressBook's merge())
     * but may not be a merge target (this.merge() will throw an exception).
     * 
     * @param subscription
     *            A Subscription instance pointing at a remote address book.
     * @param proxyHost hostname of proxy
     * @param proxyPort port number of proxy
     */
    public AddressBook(Subscription subscription, String proxyHost, int proxyPort) {
        Map<String, String> a = null;
        File subf = null;
        try {
            File tmp = SecureFile.createTempFile("addressbook", null, I2PAppContext.getGlobalContext().getTempDir());
            EepGet get = new EepGet(I2PAppContext.getGlobalContext(), true,
                    proxyHost, proxyPort, 0, -1l, MAX_SUB_SIZE, tmp.getAbsolutePath(), null,
                    subscription.getLocation(), true, subscription.getEtag(), subscription.getLastModified(), null);
            if (get.fetch()) {
                subscription.setEtag(get.getETag());
                subscription.setLastModified(get.getLastModified());
                subscription.setLastFetched(I2PAppContext.getGlobalContext().clock().now());
                subf = tmp;
            } else {
                a = Collections.EMPTY_MAP;
                tmp.delete();
            }
        } catch (IOException ioe) {
            a = Collections.EMPTY_MAP;
        }
        this.addresses = a;
        this.subFile = subf;
        this.location = subscription.getLocation();
    }

    /**
     * Construct an AddressBook from the contents of the file at file. If the
     * file cannot be read, construct an empty AddressBook.
     * This reads the entire file into memory.
     * The resulting map is modifiable and may be a merge target.
     * 
     * @param file
     *            A File pointing at a file with lines in the format
     *            "key=value", where key is a human readable name, and value is
     *            a base64 i2p destination.
     */
    public AddressBook(File file) {
        this.location = file.toString();
        Map<String, String> a;
        try {
            a = ConfigParser.parse(file);
        } catch (IOException exp) {
            a = new HashMap();
        }
        this.addresses = a;
        this.subFile = null;
    }

    /**
     * Return an iterator over the addresses in the AddressBook.
     * @since 0.8.6
     */
    public Iterator<Map.Entry<String, String>> iterator() {
        if (this.subFile != null)
            return new ConfigIterator(this.subFile);
        return this.addresses.entrySet().iterator();
    }

    /**
     * Delete the temp file or clear the map.
     * @since 0.8.6
     */
    public void delete() {
        if (this.subFile != null) {
            this.subFile.delete();
        } else if (this.addresses != null) {
            try {
                this.addresses.clear();
            } catch (UnsupportedOperationException uoe) {}
        }
    }

    /**
     * Return the location of the file this AddressBook was constructed from.
     * 
     * @return A String representing either an abstract path, or a url,
     *         depending on how the instance was constructed.
     *         Will be null if created with the Map constructor.
     */
    public String getLocation() {
        return this.location;
    }

    /**
     * Return a string representation of the origin of the AddressBook.
     * 
     * @return A String representing the origin of the AddressBook.
     */
    @Override
    public String toString() {
        if (this.location != null)
            return "Book from " + this.location;
        return "Map containing " + this.addresses.size() + " entries";
    }

    private static final int MIN_DEST_LENGTH = 516;
    private static final int MAX_DEST_LENGTH = MIN_DEST_LENGTH + 100;  // longer than any known cert type for now

    /**
     * Do basic validation of the hostname
     * hostname was already converted to lower case by ConfigParser.parse()
     */
    public static boolean isValidKey(String host) {
	return
		host.endsWith(".i2p") &&
		host.length() > 4 &&
		host.length() <= 67 &&          // 63 + ".i2p"
                (! host.startsWith(".")) &&
                (! host.startsWith("-")) &&
                host.indexOf(".-") < 0 &&
                host.indexOf("-.") < 0 &&
		host.indexOf("..") < 0 &&
                // IDN - basic check, not complete validation
                (host.indexOf("--") < 0 || host.startsWith("xn--") || host.indexOf(".xn--") > 0) &&
                host.replaceAll("[a-z0-9.-]", "").length() == 0 &&
                // Base32 spoofing (52chars.i2p)
                (! (host.length() == 56 && host.substring(0,52).replaceAll("[a-z2-7]", "").length() == 0)) &&
                // ... or maybe we do Base32 this way ...
                (! host.equals("b32.i2p")) &&
                (! host.endsWith(".b32.i2p")) &&
                // some reserved names that may be used for local configuration someday
                (! host.equals("proxy.i2p")) &&
                (! host.equals("router.i2p")) &&
                (! host.equals("console.i2p")) &&
                (! host.endsWith(".proxy.i2p")) &&
                (! host.endsWith(".router.i2p")) &&
                (! host.endsWith(".console.i2p"))
                ;	
    }

    /**
     * Do basic validation of the b64 dest, without bothering to instantiate it
     */
    private static boolean isValidDest(String dest) {
	return
                // null cert ends with AAAA but other zero-length certs would be AA
		((dest.length() == MIN_DEST_LENGTH && dest.endsWith("AA")) ||
		 (dest.length() > MIN_DEST_LENGTH && dest.length() <= MAX_DEST_LENGTH)) &&
		// B64 comes in groups of 2, 3, or 4 chars, but never 1
		((dest.length() % 4) != 1) &&
                dest.replaceAll("[a-zA-Z0-9~-]", "").length() == 0
                ;	
    }

    /**
     * Merge this AddressBook with AddressBook other, writing messages about new
     * addresses or conflicts to log. Addresses in AddressBook other that are
     * not in this AddressBook are added to this AddressBook. In case of a
     * conflict, addresses in this AddressBook take precedence
     * 
     * @param other
     *            An AddressBook to merge with.
     * @param overwrite True to overwrite
     * @param log
     *            The log to write messages about new addresses or conflicts to.
     *
     * @throws IllegalStateException if this was created with the Subscription constructor.
     */
    public void merge(AddressBook other, boolean overwrite, Log log) {
        if (this.addresses == null)
            throw new IllegalStateException();
        for (Iterator<Map.Entry<String, String>> iter = other.iterator(); iter.hasNext(); ) {
            Map.Entry<String, String> entry = iter.next();
            String otherKey = entry.getKey();
            String otherValue = entry.getValue();

            if (isValidKey(otherKey) && isValidDest(otherValue)) {
                if (this.addresses.containsKey(otherKey) && !overwrite) {
                    if (DEBUG && log != null &&
                        !this.addresses.get(otherKey).equals(otherValue)) {
                        log.append("Conflict for " + otherKey + " from "
                                + other.location
                                + ". Destination in remote address book is "
                                + otherValue);
                    }
                } else if (!this.addresses.containsKey(otherKey)
                            || !this.addresses.get(otherKey).equals(otherValue)) {
                    this.addresses.put(otherKey, otherValue);
                    this.modified = true;
                    if (log != null) {
                        log.append("New address " + otherKey
                            + " added to address book. From: " + other.location);
                    }
                }
            }
        }
    }

    /**
     * Write the contents of this AddressBook out to the File file. If the file
     * cannot be writen to, this method will silently fail.
     * 
     * @param file
     *            The file to write the contents of this AddressBook too.
     *
     * @throws IllegalStateException if this was created with the Subscription constructor.
     */
    public void write(File file) {
        if (this.addresses == null)
            throw new IllegalStateException();
        if (this.modified) {
            try {
                ConfigParser.write(this.addresses, file);
            } catch (IOException exp) {
                System.err.println("Error writing addressbook " + file.getAbsolutePath() + " : " + exp.toString());
            }
        }
    }

    /**
     * Write this AddressBook out to the file it was read from. Requires that
     * AddressBook was constructed from a file on the local filesystem. If the
     * file cannot be writen to, this method will silently fail.
     *
     * @throws IllegalStateException if this was not created with the File constructor.
     */
    public void write() {
        if (this.location == null || this.location.startsWith("http://"))
            throw new IllegalStateException();
        this.write(new File(this.location));
    }

    @Override
    protected void finalize() {
        delete();
    }
}
