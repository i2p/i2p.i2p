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

import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.io.File;
import java.io.IOException;

/**
 * A list of Subscriptions loaded from a file.
 * 
 * @author Ragnarok
 *  
 */
public class SubscriptionList {

    private List subscriptions;

    private File etagsFile;

    private File lastModifiedFile;

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
     */
    public SubscriptionList(File locationsFile, File etagsFile,
            File lastModifiedFile) {
        this.subscriptions = new LinkedList();
        this.etagsFile = etagsFile;
        this.lastModifiedFile = lastModifiedFile;
        List locations;
        Map etags;
        Map lastModified;
        String location;
        try {
            locations = ConfigParser.parseSubscriptions(locationsFile);
        } catch (IOException exp) {
            locations = new LinkedList();
        }
        try {
            etags = ConfigParser.parse(etagsFile);
        } catch (IOException exp) {
            etags = new HashMap();
        }
        try {
            lastModified = ConfigParser.parse(lastModifiedFile);
        } catch (IOException exp) {
            lastModified = new HashMap();
        }
        Iterator iter = locations.iterator();
        while (iter.hasNext()) {
            location = (String) iter.next();
            subscriptions.add(new Subscription(location, (String) etags
                    .get(location), (String) lastModified.get(location)));
        }

        iter = this.iterator();
    }

    /**
     * Return an iterator over the AddressBooks represented by the Subscriptions
     * in this SubscriptionList.
     * 
     * @return A SubscriptionIterator.
     */
    public SubscriptionIterator iterator() {
        return new SubscriptionIterator(this.subscriptions);
    }

    /**
     * Write the etag and last-modified headers for each Subscription to files.
     */
    public void write() {
        Iterator iter = this.subscriptions.iterator();
        Subscription sub;
        Map etags = new HashMap();
        Map lastModified = new HashMap();
        while (iter.hasNext()) {
            sub = (Subscription) iter.next();
            if (sub.getEtag() != null) {
                etags.put(sub.getLocation(), sub.getEtag());
            }
            if (sub.getLastModified() != null) {
                lastModified.put(sub.getLocation(), sub.getLastModified());
            }
        }
        try {
            ConfigParser.write(etags, this.etagsFile);
            ConfigParser.write(lastModified, this.lastModifiedFile);
        } catch (IOException exp) {
        }
    }
}