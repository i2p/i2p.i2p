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
import java.util.HashMap;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.util.EepGet;

/**
 * An address book for storing human readable names mapped to base64 i2p
 * destinations. AddressBooks can be created from local and remote files, merged
 * together, and written out to local files.
 * 
 * @author Ragnarok
 *  
 */
class AddressBook {

    private String location;

    private Map<String, String> addresses;

    private boolean modified;

    /**
     * Construct an AddressBook from the contents of the Map addresses.
     * 
     * @param addresses
     *            A Map containing human readable addresses as keys, mapped to
     *            base64 i2p destinations.
     */
    public AddressBook(Map<String, String> addresses) {
        this.addresses = addresses;
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
     * @param subscription
     *            A Subscription instance pointing at a remote address book.
     * @param proxyHost hostname of proxy
     * @param proxyPort port number of proxy
     */
    public AddressBook(Subscription subscription, String proxyHost, int proxyPort) {
        File tmp = new File(I2PAppContext.getGlobalContext().getTempDir(), "addressbook.tmp");
        this.location = subscription.getLocation();
        EepGet get = new EepGet(I2PAppContext.getGlobalContext(), true,
                proxyHost, proxyPort, 0, -1l, MAX_SUB_SIZE, tmp.getAbsolutePath(), null,
                subscription.getLocation(), true, subscription.getEtag(), subscription.getLastModified(), null);
        if (get.fetch()) {
            subscription.setEtag(get.getETag());
            subscription.setLastModified(get.getLastModified());
            subscription.setLastFetched(I2PAppContext.getGlobalContext().clock().now());
        }
        try {            
            this.addresses = ConfigParser.parse(tmp);
        } catch (IOException exp) {
            this.addresses = new HashMap();
        }
        tmp.delete();
    }

    /**
     * Construct an AddressBook from the contents of the file at file. If the
     * file cannot be read, construct an empty AddressBook
     * 
     * @param file
     *            A File pointing at a file with lines in the format
     *            "key=value", where key is a human readable name, and value is
     *            a base64 i2p destination.
     */
    public AddressBook(File file) {
        this.location = file.toString();
        try {
            this.addresses = ConfigParser.parse(file);
        } catch (IOException exp) {
            this.addresses = new HashMap();
        }
    }

    /**
     * Return a Map containing the addresses in the AddressBook.
     * 
     * @return A Map containing the addresses in the AddressBook, where the key
     *         is a human readable name, and the value is a base64 i2p
     *         destination.
     */
    public Map<String, String> getAddresses() {
        return this.addresses;
    }

    /**
     * Return the location of the file this AddressBook was constructed from.
     * 
     * @return A String representing either an abstract path, or a url,
     *         depending on how the instance was constructed.
     */
    public String getLocation() {
        return this.location;
    }

    /**
     * Return a string representation of the contents of the AddressBook.
     * 
     * @return A String representing the contents of the AddressBook.
     */
    @Override
    public String toString() {
        return this.addresses.toString();
    }

    private static final int MIN_DEST_LENGTH = 516;
    private static final int MAX_DEST_LENGTH = MIN_DEST_LENGTH + 100;  // longer than any known cert type for now

    /**
     * Do basic validation of the hostname and dest
     * hostname was already converted to lower case by ConfigParser.parse()
     */
    private static boolean valid(String host, String dest) {
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
                (! host.endsWith(".console.i2p")) &&

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
     */
    public void merge(AddressBook other, boolean overwrite, Log log) {
        for (Map.Entry<String, String> entry : other.addresses.entrySet()) {
            String otherKey = entry.getKey();
            String otherValue = entry.getValue();

            if (valid(otherKey, otherValue)) {
                if (this.addresses.containsKey(otherKey) && !overwrite) {
                    if (!this.addresses.get(otherKey).equals(otherValue)
                            && log != null) {
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
     */
    public void write(File file) {
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
     */
    public void write() {
        this.write(new File(this.location));
    }
}
