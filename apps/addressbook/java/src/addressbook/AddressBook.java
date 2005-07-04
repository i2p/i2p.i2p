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

package addressbook;

import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.net.URL;
import java.net.HttpURLConnection;
import java.io.File;
import java.io.IOException;

/**
 * An address book for storing human readable names mapped to base64 i2p
 * destinations. AddressBooks can be created from local and remote files, merged
 * together, and written out to local files.
 * 
 * @author Ragnarok
 *  
 */
public class AddressBook {

    private String location;

    private Map addresses;

    private boolean modified;

    /**
     * Construct an AddressBook from the contents of the Map addresses.
     * 
     * @param addresses
     *            A Map containing human readable addresses as keys, mapped to
     *            base64 i2p destinations.
     */
    public AddressBook(Map addresses) {
        this.addresses = addresses;
    }

    /**
     * Construct an AddressBook from the contents of the file at url. If the
     * remote file cannot be read, construct an empty AddressBook
     * 
     * @param url
     *            A URL pointing at a file with lines in the format "key=value",
     *            where key is a human readable name, and value is a base64 i2p
     *            destination.
     */
    public AddressBook(URL url) {
        this.location = url.getHost();

        try {
            this.addresses = ConfigParser.parse(url);
        } catch (IOException exp) {
            this.addresses = new HashMap();
        }
    }

    /**
     * Construct an AddressBook from the Subscription subscription. If the
     * address book at subscription has not changed since the last time it was
     * read or cannot be read, return an empty AddressBook.
     * 
     * @param subscription
     *            A Subscription instance pointing at a remote address book.
     */
    public AddressBook(Subscription subscription) {
        this.location = subscription.getLocation();

        try {
//            EepGet get = new EepGet(I2PAppContext.getGlobalContext(), true, )
            URL url = new URL(subscription.getLocation());
            HttpURLConnection connection = (HttpURLConnection) url
                    .openConnection();
            if (subscription.getEtag() != null) {
                connection.addRequestProperty("If-None-Match", subscription
                        .getEtag());
            }
            if (subscription.getLastModified() != null) {
                connection.addRequestProperty("If-Modified-Since", subscription
                        .getLastModified());
            }
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                connection.disconnect();
                this.addresses = new HashMap();
                return;
            }
            if (connection.getHeaderField("ETag") != null) {
                subscription.setEtag(connection.getHeaderField("ETag"));
            }
            if (connection.getHeaderField("Last-Modified") != null) {
                subscription.setLastModified(connection
                        .getHeaderField("Last-Modified"));
            }
        } catch (IOException exp) {
        }

        try {
            this.addresses = ConfigParser.parse(new URL(subscription
                    .getLocation()));
        } catch (IOException exp) {
            this.addresses = new HashMap();
        }
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
    public Map getAddresses() {
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
    public String toString() {
        return this.addresses.toString();
    }

    /**
     * Merge this AddressBook with AddressBook other, writing messages about new
     * addresses or conflicts to log. Addresses in AddressBook other that are
     * not in this AddressBook are added to this AddressBook. In case of a
     * conflict, addresses in this AddressBook take precedence
     * 
     * @param other
     *            An AddressBook to merge with.
     * @param log
     *            The log to write messages about new addresses or conflicts to.
     */
    public void merge(AddressBook other, Log log) {
        Iterator otherIter = other.addresses.keySet().iterator();

        while (otherIter.hasNext()) {
            String otherKey = (String) otherIter.next();
            String otherValue = (String) other.addresses.get(otherKey);

            if (otherKey.endsWith(".i2p") && otherValue.length() >= 516) {
                if (this.addresses.containsKey(otherKey)) {
                    if (!this.addresses.get(otherKey).equals(otherValue)
                            && log != null) {
                        log.append("Conflict for " + otherKey + " from "
                                + other.location
                                + ". Destination in remote address book is "
                                + otherValue);
                    }
                } else {
                    this.addresses.put(otherKey, otherValue);
                    this.modified = true;
                    if (log != null) {
                        log.append("New address " + otherKey
                                + " added to address book.");
                    }
                }
            }
        }
    }

    /**
     * Merge this AddressBook with other, without logging.
     * 
     * @param other
     *            An AddressBook to merge with.
     */
    public void merge(AddressBook other) {
        this.merge(other, null);
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