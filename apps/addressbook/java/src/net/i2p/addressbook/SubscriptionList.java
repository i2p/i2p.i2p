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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A list of Subscriptions loaded from a file.
 * 
 * @author Ragnarok
 *  
 */
class SubscriptionList implements Iterable<AddressBook> {

    private final List<Subscription> subscriptions;
    private final File etagsFile;
    private final File lastModifiedFile;
    private final File lastFetchedFile;
    private final long delay;
    private final String proxyHost;
    private final int proxyPort;

    /**
     * Construct a SubscriptionList using the urls from locationsFile and, if
     * available, the etags and last-modified headers loaded from etagsFile and
     * lastModifiedFile.
     * 
     * @param locationsFile
     *            A file containing one url on each line.
     * @param etagsFile
     *            A file containg the etag headers used for conditional GET. The
     *            file is in the format "url=etag".
     * @param lastModifiedFile
     *            A file containg the last-modified headers used for conditional
     *            GET. The file is in the format "url=leastmodified".
     * @param delay the minimum delay since last fetched for the iterator to actually fetch
     * @param defaultSubs default subscription file
     * @param proxyHost proxy hostname
     * @param proxyPort proxy port number
     */
    public SubscriptionList(File locationsFile, File etagsFile,
            File lastModifiedFile, File lastFetchedFile, long delay, List<String> defaultSubs, String proxyHost, 
            int proxyPort) {
        this.subscriptions = new ArrayList<Subscription>(4);
        this.etagsFile = etagsFile;
        this.lastModifiedFile = lastModifiedFile;
        this.lastFetchedFile = lastFetchedFile;
        this.delay = delay;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        Map<String, String> etags;
        Map<String, String> lastModified;
        Map<String, String> lastFetched;
        List<String> locations = ConfigParser.parseSubscriptions(locationsFile, 
                defaultSubs);
        try {
            etags = ConfigParser.parse(etagsFile);
        } catch (IOException exp) {
            etags = Collections.<String, String>emptyMap();
        }
        try {
            lastModified = ConfigParser.parse(lastModifiedFile);
        } catch (IOException exp) {
            lastModified = Collections.<String, String>emptyMap();
        }
        try {
            lastFetched = ConfigParser.parse(lastFetchedFile);
        } catch (IOException exp) {
            lastFetched = Collections.<String, String>emptyMap();
        }
        for (String location : locations) {
            this.subscriptions.add(new Subscription(location, etags.get(location),
                                   lastModified.get(location),
                                   lastFetched.get(location)));
        }
    }
    
    /**
     * Testing only.
     * 
     * @param hoststxt path to a local file used as the test 'subscription' input
     * @since 0.9.26
     */
    public SubscriptionList(String hoststxt) {
        File dummy = new File("/dev/null");
        this.etagsFile = dummy;
        this.lastModifiedFile = dummy;
        this.lastFetchedFile = dummy;
        this.delay = 0;
        this.proxyHost = "127.0.0.1";
        this.proxyPort = 4444;
        Subscription sub = new Subscription("file:" + hoststxt, null, null, null);
        this.subscriptions = Collections.singletonList(sub);
    }
    
    /**
     * Return an iterator over the AddressBooks represented by the Subscriptions
     * in this SubscriptionList.
     * 
     * @return A SubscriptionIterator.
     */
    public SubscriptionIterator iterator() {
        return new SubscriptionIterator(this.subscriptions, this.delay, this.proxyHost, 
                this.proxyPort);
    }

    /**
     * Write the etag and last-modified headers,
     * and the last-fetched time, for each Subscription to files.
     * BUG - If the subscription URL is a cgi containing an '=' the files
     * won't be read back correctly; the '=' should be escaped.
     */
    public void write() {
        int sz = subscriptions.size();
        Map<String, String> etags = new HashMap<String, String>(sz);
        Map<String, String> lastModified = new HashMap<String, String>(sz);
        Map<String, String> lastFetched = new HashMap<String, String>(sz);
        for (Subscription sub : this.subscriptions) {
            if (sub.getEtag() != null) {
                etags.put(sub.getLocation(), sub.getEtag());
            }
            if (sub.getLastModified() != null) {
                lastModified.put(sub.getLocation(), sub.getLastModified());
            }
            lastFetched.put(sub.getLocation(), Long.toString(sub.getLastFetched()));
        }
        try {
            ConfigParser.write(etags, this.etagsFile);
        } catch (IOException exp) {}
        try {
            ConfigParser.write(lastModified, this.lastModifiedFile);
        } catch (IOException exp) {}
        try {
            ConfigParser.write(lastFetched, this.lastFetchedFile);
        } catch (IOException exp) {}
    }
}
