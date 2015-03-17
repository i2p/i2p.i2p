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

/**
 * A subscription to a remote address book.
 * 
 * @author Ragnarok
 *  
 */
class Subscription {

    private String location;

    private String etag;

    private String lastModified;
    private long lastFetched;

    /**
     * Construct a Subscription pointing to the address book at location, that
     * was last read at the time represented by etag and lastModified.
     * 
     * @param location
     *            A String representing a url to a remote address book.
     * @param etag
     *            The etag header that we recieved the last time we read this
     *            subscription.
     * @param lastModified
     *            the last-modified header we recieved the last time we read
     *            this subscription.
     * @param lastFetched when the subscription was last fetched (Java time, as a String)
     */
    public Subscription(String location, String etag, String lastModified, String lastFetched) {
        this.location = location;
        this.etag = etag;
        this.lastModified = lastModified;
        if (lastFetched != null) {
            try {
                this.lastFetched = Long.parseLong(lastFetched);
            } catch (NumberFormatException nfe) {}
        }
    }

    /**
     * Return the location this Subscription points at.
     * 
     * @return A String representing a url to a remote address book.
     */
    public String getLocation() {
        return this.location;
    }

    /**
     * Return the etag header that we recieved the last time we read this
     * subscription.
     * 
     * @return A String containing the etag header.
     */
    public String getEtag() {
        return this.etag;
    }

    /**
     * Set the etag header.
     * 
     * @param etag
     *            A String containing the etag header.
     */
    public void setEtag(String etag) {
        this.etag = etag;
    }

    /**
     * Return the last-modified header that we recieved the last time we read
     * this subscription.
     * 
     * @return A String containing the last-modified header.
     */
    public String getLastModified() {
        return this.lastModified;
    }

    /**
     * Set the last-modified header.
     * 
     * @param lastModified
     *            A String containing the last-modified header.
     */
    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    /** @since 0.8.2 */
    public long getLastFetched() {
        return this.lastFetched;
    }

    /** @since 0.8.2 */
    public void setLastFetched(long t) {
        this.lastFetched = t;
    }
}
