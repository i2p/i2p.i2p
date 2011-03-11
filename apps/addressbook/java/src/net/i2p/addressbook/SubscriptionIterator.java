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

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;  // debug

/**
 * An iterator over the subscriptions in a SubscriptionList.  Note that this iterator
 * returns AddressBook objects, and not Subscription objects.
 * Yes, the EepGet fetch() is done in here in next().
 * 
 * @author Ragnarok
 */
class SubscriptionIterator implements Iterator<AddressBook> {

    private Iterator<Subscription> subIterator;
    private String proxyHost;
    private int proxyPort;
    private final long delay;

    /**
     * Construct a SubscriptionIterator using the Subscriprions in List subscriptions.
     * 
     * @param subscriptions
     *            List of Subscription objects that represent address books.
     * @param delay the minimum delay since last fetched for the iterator to actually fetch
     * @param proxyHost proxy hostname
     * @param proxyPort proxt port number
     */
    public SubscriptionIterator(List<Subscription> subscriptions, long delay, String proxyHost, int proxyPort) {
        this.subIterator = subscriptions.iterator();
        this.delay = delay;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
    }

    
    /* (non-Javadoc)
     * @see java.util.Iterator#hasNext()
     */
    public boolean hasNext() {
        return this.subIterator.hasNext();
    }

    /**
     * Yes, the EepGet fetch() is done in here in next().
     *
     * see java.util.Iterator#next()
     * @return an AddressBook (empty if the minimum delay has not been met)
     */
    public AddressBook next() {
        Subscription sub = this.subIterator.next();
        if (sub.getLastFetched() + this.delay < I2PAppContext.getGlobalContext().clock().now()) {
            //System.err.println("Fetching addressbook from " + sub.getLocation());
            return new AddressBook(sub, this.proxyHost, this.proxyPort);
        } else {
            //System.err.println("Addressbook " + sub.getLocation() + " was last fetched " + 
            //                   DataHelper.formatDuration(I2PAppContext.getGlobalContext().clock().now() - sub.getLastFetched()) +
            //                   " ago but the minimum delay is " +
            //                   DataHelper.formatDuration(this.delay));
            return new AddressBook(Collections.EMPTY_MAP);
        }
    }

    /* (non-Javadoc)
     * @see java.util.Iterator#remove()
     */
    public void remove() {
        throw new UnsupportedOperationException();
    }
}
