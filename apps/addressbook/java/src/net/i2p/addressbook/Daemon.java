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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.HostTxtEntry;
import net.i2p.client.naming.NamingService;
import net.i2p.client.naming.SingleFileNamingService;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.OrderedProperties;
import net.i2p.util.SecureDirectory;
import net.i2p.util.SystemVersion;

/**
 * Main class of addressbook.  Performs updates, and runs the main loop.
 * As of 0.9.30, package private, run with DaemonThread.
 * 
 * @author Ragnarok
 *
 */
class Daemon {
    public static final String VERSION = "2.0.4";
    private volatile boolean _running;
    private static final boolean DEBUG = false;
    // If you change this, change in SusiDNS SubscriptionBean also
    private static final String DEFAULT_SUB = "http://i2p-projekt.i2p/hosts.txt";
    /** @since 0.9.12 */
    static final String OLD_DEFAULT_SUB = "http://www.i2p2.i2p/hosts.txt";
    /** Any properties we receive from the subscription, we store to the
     *  addressbook with this prefix, so it knows it's part of the signature.
     *  This is also chosen so that it can't be spoofed.
     */
    private static final String RCVD_PROP_PREFIX = "=";
    private static final boolean MUST_VALIDATE = false;
    
    /**
     * Update the router and published address books using remote data from the
     * subscribed address books listed in subscriptions.
     * 
     * @param master
     *            The master AddressBook. This address book is never
     *            overwritten, so it is safe for the user to write to.
     *            It is only merged to the published addressbook.
     *            May be null.
     * @param router
     *            The router AddressBook. This is the address book read by
     *            client applications.
     * @param published
     *            The published AddressBook. This address book is published on
     *            the user's eepsite so that others may subscribe to it.
     *            May be null.
     *            If non-null, overwrite with the new addressbook.
     * @param subscriptions
     *            A SubscriptionList listing the remote address books to update
     *            from.
     * @param log
     *            The log to write changes and conflicts to.
     *            May be null.
     */
    public static void update(AddressBook master, AddressBook router,
            File published, SubscriptionList subscriptions, Log log) {
        for (AddressBook book : subscriptions) {
            // yes, the EepGet fetch() is done in next()
            router.merge(book, false, log);
        }
        router.write();
        if (published != null) {
            if (master != null)
                router.merge(master, true, null);
            router.write(published);
        }
        subscriptions.write();
    }

    /**
     * Update the router and published address books using remote data from the
     * subscribed address books listed in subscriptions.
     * Merging of the "master" addressbook is NOT supported.
     * 
     * @param router
     *            The NamingService to update, generally the root NamingService from the context.
     * @param published
     *            The published AddressBook. This address book is published on
     *            the user's eepsite so that others may subscribe to it.
     *            May be null.
     *            If non-null, overwrite with the new addressbook.
     * @param subscriptions
     *            A SubscriptionList listing the remote address books to update
     *            from.
     * @param log
     *            The log to write changes and conflicts to.
     *            May be null.
     * @since 0.8.7
     */
    public static void update(NamingService router, File published, SubscriptionList subscriptions, Log log) {
        // If the NamingService is a database, we look up as we go.
        // If it is a text file, we do things differently, to avoid O(n**2) behavior
        // when scanning large subscription results (i.e. those that return the whole file, not just the new entries) -
        // we load all the known hostnames into a Set one time.
        // This also has the advantage of not flushing the NamingService's LRU cache.
        String nsClass = router.getClass().getSimpleName();
        boolean isTextFile = nsClass.equals("HostsTxtNamingService") || nsClass.equals("SingleFileNamingService");
        Set<String> knownNames;
        if (isTextFile) {
            // load the hostname set
            Properties opts = new Properties();
            opts.setProperty("file", "hosts.txt");
            knownNames = router.getNames(opts);
        } else {
            knownNames = null;
        }
        NamingService publishedNS;
        if (published != null) {
            publishedNS = new SingleFileNamingService(I2PAppContext.getGlobalContext(), published.getAbsolutePath());
        } else {
            publishedNS = null;
        }

        Iterator<AddressBook> iter = subscriptions.iterator();
        while (iter.hasNext()) {
            // yes, the EepGet fetch() is done in next()
            long start = System.currentTimeMillis();
            AddressBook addressbook = iter.next();
            // SubscriptionIterator puts in a dummy AddressBook with no location if no fetch is done
            if (DEBUG && log != null && addressbook.getLocation() != null) {
                long end = System.currentTimeMillis();
                log.append("Fetch of " + addressbook.getLocation() + " took " + (end - start));
            }
            Iterator<Map.Entry<String, HostTxtEntry>> iter2 = addressbook.iterator();
            try {
                update(router, knownNames, publishedNS, addressbook, iter2, log);
            } finally {
                if (iter2 instanceof HostTxtIterator)
                    ((HostTxtIterator) iter2).close();
                addressbook.delete();
            }
        }  // subscriptions
        subscriptions.write();
    }

    /**
     *  @param knownNames only non-null if router book is a text file
     *  @param publishedNS only non-null if we have a published address book
     *  @since 0.9.33 split out from above
     */
    private static void update(NamingService router, Set<String> knownNames,
                               NamingService publishedNS, AddressBook addressbook,
                               Iterator<Map.Entry<String, HostTxtEntry>> iter, Log log) {
            long start = System.currentTimeMillis();
            int old = 0, nnew = 0, invalid = 0, conflict = 0, total = 0;
            int deleted = 0;
            while(iter.hasNext()) {
                Map.Entry<String, HostTxtEntry> entry = iter.next();
                total++;
                // may be null for 'remove' entries
                String key = entry.getKey();
                boolean isKnown;
                // NOT set for text file NamingService
                Destination oldDest;
                if (knownNames != null) {
                    oldDest = null;
                    isKnown = key != null ? knownNames.contains(key) : null;
                } else {
                    oldDest = key != null ? router.lookup(key) : null;
                    isKnown = oldDest != null;
                }
                try {
                    HostTxtEntry he = entry.getValue();
                    Properties hprops = he.getProps();
                    boolean mustValidate = MUST_VALIDATE || hprops != null;
                    String action = hprops != null ? hprops.getProperty(HostTxtEntry.PROP_ACTION) : null;
                    if (key == null && !he.hasValidRemoveSig()) {
                        if (log != null) {
                            log.append("Bad signature of action " + action + " for key " +
                                       hprops.getProperty(HostTxtEntry.PROP_NAME) +
                                       ". From: " + addressbook.getLocation());
                        }
                        invalid++;
                    } else if (key != null && mustValidate && !he.hasValidSig()) {
                        if (log != null) {
                            log.append("Bad signature of action " + action + " for key " + key +
                                       ". From: " + addressbook.getLocation());
                        }
                        invalid++;
                    } else if (action != null || !isKnown) {
                        if (key != null && AddressBook.isValidKey(key)) {
                            Destination dest = new Destination(he.getDest());
                            Properties props = new OrderedProperties();
                            props.setProperty("s", addressbook.getLocation());
                            boolean allowExistingKeyInPublished = false;
                            if (mustValidate) {
                                // sig checked above
                                props.setProperty("v", "true");
                            }
                            if (hprops != null) {
                                // merge in all the received properties
                                for (Map.Entry<Object, Object> e : hprops.entrySet()) {
                                    // Add prefix to indicate received property
                                    props.setProperty(RCVD_PROP_PREFIX + e.getKey(), (String) e.getValue());
                                }
                            }
                            if (action != null) {
                                // Process commands. hprops is non-null.
                                // Must handle isKnown in each case.
                                if (action.equals(HostTxtEntry.ACTION_ADDDEST)) {
                                    // Add an alternate destination (new crypto) for existing hostname
                                    // Requires new NamingService support if the key exists
                                    String polddest = hprops.getProperty(HostTxtEntry.PROP_OLDDEST);
                                    if (polddest != null) {
                                        Destination pod = new Destination(polddest);
                                        List<Destination> pod2 = router.lookupAll(key);
                                        if (pod2 == null) {
                                            // we didn't know it before, so we'll add it
                                            // check inner sig anyway
                                            if (!he.hasValidInnerSig()) {
                                                logInner(log, action, key, addressbook);
                                                invalid++;
                                                continue;
                                            }
                                        } else if (pod2.contains(dest)) {
                                            // we knew it before, with the same dest
                                            old++;
                                            continue;
                                        } else if (pod2.contains(pod)) {
                                            // checks out, so verify the inner sig
                                            if (!he.hasValidInnerSig()) {
                                                logInner(log, action, key, addressbook);
                                                invalid++;
                                                continue;
                                            }
                                            // TODO Requires NamingService support
                                            // if (isTextFile), do we replace or not? check sigType.isAvailable()
                                            boolean success = router.addDestination(key, dest, props);
                                            if (log != null) {
                                                if (success)
                                                    log.append("Additional address for " + key +
                                                               " added to address book. From: " + addressbook.getLocation());
                                                else
                                                    log.append("Failed to add additional address for " + key +
                                                               " From: " + addressbook.getLocation());
                                            }
                                            // now update the published addressbook
                                            // ditto
                                            if (publishedNS != null) {
                                                // FIXME this fails, no support in SFNS
                                                success = publishedNS.addDestination(key, dest, props);
                                                if (log != null && !success)
                                                    log.append("Add to published address book " + publishedNS.getName() + " failed for " + key);
                                            }
                                            nnew++;
                                            continue;
                                        } else {
                                            // mismatch, disallow
                                            logMismatch(log, action, key, pod2, he.getDest(), addressbook);
                                            invalid++;
                                            continue;
                                        }
                                    } else {
                                        logMissing(log, action, key, addressbook);
                                        invalid++;
                                        continue;
                                    }
                                } else if (action.equals(HostTxtEntry.ACTION_ADDNAME)) {
                                    // Add an alias for an existing hostname, same dest
                                    if (isKnown) {
                                        // could be same or different dest
                                        old++;
                                        continue;
                                    }
                                    String poldname = hprops.getProperty(HostTxtEntry.PROP_OLDNAME);
                                    if (poldname != null) {
                                        List<Destination> pod = router.lookupAll(poldname);
                                        if (pod == null) {
                                            // we didn't have the old one, so we'll add the new one
                                        } else if (pod.contains(dest)) {
                                            // checks out, so we'll add the new one
                                        } else {
                                            // mismatch, disallow
                                            logMismatch(log, action, key, pod, he.getDest(), addressbook);
                                            invalid++;
                                            continue;
                                        }
                                    } else {
                                        logMissing(log, action, key, addressbook);
                                        invalid++;
                                        continue;
                                    }
                                } else if (action.equals(HostTxtEntry.ACTION_ADDSUBDOMAIN)) {
                                    // add a subdomain with verification
                                    if (isKnown) {
                                        old++;
                                        continue;
                                    }
                                    String polddest = hprops.getProperty(HostTxtEntry.PROP_OLDDEST);
                                    String poldname = hprops.getProperty(HostTxtEntry.PROP_OLDNAME);
                                    if (polddest != null && poldname != null) {
                                        // check for valid subdomain
                                        if (!AddressBook.isValidKey(poldname) ||
                                            key.indexOf('.' + poldname) <= 0) {
                                            if (log != null)
                                                log.append("Action: " + action + " failed because" +
                                                           " old name " + poldname +
                                                           " is invalid" +
                                                           ". From: " + addressbook.getLocation());
                                            invalid++;
                                            continue;
                                        }
                                        Destination pod = new Destination(polddest);
                                        List<Destination> pod2 = router.lookupAll(poldname);
                                        if (pod2 == null) {
                                            // we didn't have the old name
                                            // check inner sig anyway
                                            if (!he.hasValidInnerSig()) {
                                                logInner(log, action, key, addressbook);
                                                invalid++;
                                                continue;
                                            }
                                        } else if (pod2.contains(pod)) {
                                            // checks out, so verify the inner sig
                                            if (!he.hasValidInnerSig()) {
                                                logInner(log, action, key, addressbook);
                                                invalid++;
                                                continue;
                                            }
                                        } else {
                                            // mismatch, disallow
                                            logMismatch(log, action, key, pod2, polddest, addressbook);
                                            invalid++;
                                            continue;
                                        }
                                    } else {
                                        logMissing(log, action, key, addressbook);
                                        invalid++;
                                        continue;
                                    }
                                } else if (action.equals(HostTxtEntry.ACTION_CHANGEDEST)) {
                                    // change destination on an existing entry
                                    // This removes all previous destinations under that hostname,
                                    // is this what we want?
                                    String polddest = hprops.getProperty(HostTxtEntry.PROP_OLDDEST);
                                    if (polddest != null) {
                                        Destination pod = new Destination(polddest);
                                        List<Destination> pod2 = router.lookupAll(key);
                                        if (pod2 == null) {
                                            // we didn't have the old name
                                            // check inner sig anyway
                                            if (!he.hasValidInnerSig()) {
                                                logInner(log, action, key, addressbook);
                                                invalid++;
                                                continue;
                                            }
                                        } else if (pod2.contains(dest)) {
                                            // we already have the new dest
                                            old++;
                                            continue;
                                        } else if (pod2.contains(pod)) {
                                            // checks out, so verify the inner sig
                                            if (!he.hasValidInnerSig()) {
                                                logInner(log, action, key, addressbook);
                                                invalid++;
                                                continue;
                                            }
                                            if (log != null) {
                                                if (pod2.size() == 1)
                                                    log.append("Changing destination for " + key +
                                                               ". From: " + addressbook.getLocation());
                                                else
                                                    log.append("Replacing " + pod2.size() + " destinations for " + key +
                                                               ". From: " + addressbook.getLocation());
                                            }
                                            allowExistingKeyInPublished = true;
                                            props.setProperty("m", Long.toString(I2PAppContext.getGlobalContext().clock().now()));
                                        } else {
                                            // mismatch, disallow
                                            logMismatch(log, action, key, pod2, polddest, addressbook);
                                            invalid++;
                                            continue;
                                        }
                                    } else {
                                        logMissing(log, action, key, addressbook);
                                        invalid++;
                                        continue;
                                    }
                                } else if (action.equals(HostTxtEntry.ACTION_CHANGENAME)) {
                                    // Delete old name, replace with new
                                    // This removes all previous destinations under that hostname,
                                    // is this what we want?
                                    if (isKnown) {
                                        old++;
                                        continue;
                                    }
                                    String poldname = hprops.getProperty(HostTxtEntry.PROP_OLDNAME);
                                    if (poldname != null) {
                                        List<Destination> pod = router.lookupAll(poldname);
                                        if (pod == null) {
                                            // we didn't have the old name
                                        } else if (pod.contains(dest)) {
                                            // checks out, so we'll delete it
                                            if (knownNames != null)
                                                knownNames.remove(poldname);
                                            boolean success = router.remove(poldname, dest);
                                            if (success)
                                                deleted++;
                                            if (log != null) {
                                                if (success)
                                                    log.append("Removed: " + poldname +
                                                               " to be replaced with " + key +
                                                               ". From: " + addressbook.getLocation());
                                                else
                                                    log.append("Remove failed for: " + poldname +
                                                               " to be replaced with " + key +
                                                               ". From: " + addressbook.getLocation());
                                            }
                                            // now update the published addressbook
                                            if (publishedNS != null) {
                                                success = publishedNS.remove(poldname, dest);
                                                if (log != null && !success)
                                                    log.append("Remove from published address book " + publishedNS.getName() + " failed for " + poldname);
                                            }
                                        } else {
                                            // mismatch, disallow
                                            logMismatch(log, action, key, pod, he.getDest(), addressbook);
                                            continue;
                                        }
                                    } else {
                                        logMissing(log, action, key, addressbook);
                                        invalid++;
                                        continue;
                                    }
                                } else if (action.equals(HostTxtEntry.ACTION_REMOVE) ||
                                           action.equals(HostTxtEntry.ACTION_REMOVEALL)) {
                                    // w/o name=dest handled below
                                    if (log != null)
                                        log.append("Action: " + action + " with name=dest invalid" +
                                                   ". From: " + addressbook.getLocation());
                                    invalid++;
                                    continue;
                                } else if (action.equals(HostTxtEntry.ACTION_UPDATE)) {
                                    if (isKnown) {
                                        allowExistingKeyInPublished = true;
                                        props.setProperty("m", Long.toString(I2PAppContext.getGlobalContext().clock().now()));
                                    }
                                } else {
                                    if (log != null)
                                        log.append("Action: " + action + " unrecognized" +
                                                   ". From: " + addressbook.getLocation());
                                    invalid++;
                                    continue;
                                }
                            } // action != null
                            boolean success = router.put(key, dest, props);
                            if (log != null) {
                                if (success)
                                    log.append("New address " + key +
                                               " added to address book. From: " + addressbook.getLocation());
                                else
                                    log.append("Save to naming service " + router + " failed for new key " + key);
                            }
                            // now update the published addressbook
                            if (publishedNS != null) {
                                if (allowExistingKeyInPublished)
                                    success = publishedNS.put(key, dest, props);
                                else
                                    success = publishedNS.putIfAbsent(key, dest, props);
                                if (log != null && !success) {
                                    log.append("Save to published address book " + publishedNS.getName() + " failed for new key " + key);
                                }
                            }
                            if (knownNames != null) {
                                // keep track for later dup check
                                knownNames.add(key);
                            }
                            nnew++;
                        } else if (key == null) {
                            // 'remove' actions
                            // isKnown is false
                            if (action != null) {
                                // Process commands. hprops is non-null.
                                if (action.equals(HostTxtEntry.ACTION_REMOVE)) {
                                    // delete this entry
                                    String polddest = hprops.getProperty(HostTxtEntry.PROP_DEST);
                                    String poldname = hprops.getProperty(HostTxtEntry.PROP_NAME);
                                    if (polddest != null && poldname != null) {
                                        Destination pod = new Destination(polddest);
                                        List<Destination> pod2 = router.lookupAll(poldname);
                                        if (pod2 != null && pod2.contains(pod)) {
                                            if (knownNames != null && pod2.size() == 1)
                                                knownNames.remove(poldname);
                                            boolean success = router.remove(poldname, pod);
                                            if (success)
                                                deleted++;
                                            if (log != null) {
                                                if (success)
                                                    log.append("Removed: " + poldname +
                                                               " as requested" +
                                                               ". From: " + addressbook.getLocation());
                                                else
                                                    log.append("Remove failed for: " + poldname +
                                                               " as requested" +
                                                               ". From: " + addressbook.getLocation());
                                            }
                                            // now update the published addressbook
                                            if (publishedNS != null) {
                                                success = publishedNS.remove(poldname, pod);
                                                if (log != null && !success)
                                                    log.append("Remove from published address book " + publishedNS.getName() + " failed for " + poldname);
                                            }
                                        } else if (pod2 != null) {
                                            // mismatch, disallow
                                            logMismatch(log, action, key, pod2, polddest, addressbook);
                                            invalid++;
                                        } else {
                                            old++;
                                        }
                                    } else {
                                        logMissing(log, action, "delete", addressbook);
                                        invalid++;
                                    }
                                } else if (action.equals(HostTxtEntry.ACTION_REMOVEALL)) {
                                    // delete all entries with this destination
                                    String polddest = hprops.getProperty(HostTxtEntry.PROP_DEST);
                                    // oldname is optional, but nice because not all books support reverse lookup
                                    if (polddest != null) {
                                        Destination pod = new Destination(polddest);
                                        String poldname = hprops.getProperty(HostTxtEntry.PROP_NAME);
                                        if (poldname != null) {
                                            List<Destination> pod2 = router.lookupAll(poldname);
                                            if (pod2 != null && pod2.contains(pod)) {
                                                if (knownNames != null)
                                                    knownNames.remove(poldname);
                                                boolean success = router.remove(poldname, pod);
                                                if (success)
                                                    deleted++;
                                                if (log != null) {
                                                    if (success)
                                                        log.append("Removed: " + poldname +
                                                                   " as requested" +
                                                                   ". From: " + addressbook.getLocation());
                                                    else
                                                        log.append("Remove failed for: " + poldname +
                                                                   " as requested" +
                                                                   ". From: " + addressbook.getLocation());
                                                }
                                                // now update the published addressbook
                                                if (publishedNS != null) {
                                                    success = publishedNS.remove(poldname, pod);
                                                    if (log != null && !success)
                                                        log.append("Remove from published address book " + publishedNS.getName() + " failed for " + poldname);
                                                }
                                            } else if (pod2 != null) {
                                                // mismatch, disallow
                                                logMismatch(log, action, key, pod2, polddest, addressbook);
                                                invalid++;
                                            } else {
                                                old++;
                                            }
                                        }
                                        // reverse lookup, delete all
                                        List<String> revs = router.reverseLookupAll(pod);
                                        if (revs != null) {
                                            for (String rev : revs) {
                                                if (knownNames != null)
                                                    knownNames.remove(rev);
                                                boolean success = router.remove(rev, pod);
                                                if (success)
                                                    deleted++;
                                                if (log != null) {
                                                    if (success)
                                                        log.append("Removed: " + rev +
                                                                   " as requested" +
                                                                   ". From: " + addressbook.getLocation());
                                                    else
                                                        log.append("Remove failed for: " + rev +
                                                                   " as requested" +
                                                                   ". From: " + addressbook.getLocation());
                                                }
                                                // now update the published addressbook
                                                if (publishedNS != null) {
                                                    success = publishedNS.remove(rev, pod);
                                                    if (log != null && !success)
                                                        log.append("Remove from published address book " + publishedNS.getName() + " failed for " + rev);
                                                }
                                            }
                                        }
                                    } else {
                                        logMissing(log, action, "delete", addressbook);
                                        invalid++;
                                    }
                                } else {
                                    if (log != null)
                                        log.append("Action: " + action + " w/o name=dest unrecognized" +
                                                   ". From: " + addressbook.getLocation());
                                    invalid++;
                                }
                                continue;
                            } else {
                                if (log != null)
                                    log.append("No action in command line" +
                                               ". From: " + addressbook.getLocation());
                                invalid++;
                                continue;
                            }
                        } else if (log != null) {
                            log.append("Bad hostname " + key + ". From: "
                                   + addressbook.getLocation());
                            invalid++;
                        }        
                  /****
                    } else if (false && DEBUG && log != null) {
                        // lookup the conflict if we haven't yet (O(n**2) for text file)
                        if (isTextFile)
                            oldDest = router.lookup(key);
                        if (oldDest != null && !oldDest.toBase64().equals(entry.getValue())) {
                            log.append("Conflict for " + key + ". From: "
                                       + addressbook.getLocation()
                                       + ". Destination in remote address book is "
                                       + entry.getValue());
                            conflict++;
                        } else {
                            old++;
                        }
                   ****/
                    } else {
                        old++;
                    }
                } catch (DataFormatException dfe) {
                    if (log != null)
                        log.append("Invalid b64 for " + key + " From: " + addressbook.getLocation());
                    invalid++;
                }
            }  // entries
            if (DEBUG && log != null && total > 0) {
                log.append("Merge of " + addressbook.getLocation() + " into " + router +
                           " took " + (System.currentTimeMillis() - start) + " ms with " +
                           total + " total, " +
                           nnew + " new, " +
                           old + " old, " +
                           deleted + " deleted, " +
                           invalid + " invalid, " +
                           conflict + " conflicts");
            }
    }

    /** @since 0.9.26 */
    private static void logInner(Log log, String action, String name, AddressBook addressbook) {
        if (log != null) {
            log.append("Action: " + action + " failed because" +
                       " inner signature for key " + name +
                       " failed" +
                       ". From: " + addressbook.getLocation());
        }
    }

    /** @since 0.9.26 */
    private static void logMissing(Log log, String action, String name, AddressBook addressbook) {
        if (log != null) {
            log.append("Action: " + action + " for " + name +
                       " failed, missing required parameters" +
                       ". From: " + addressbook.getLocation());
        }
    }

    /** @since 0.9.26 */
    private static void logMismatch(Log log, String action, String name, List<Destination> dests,
                                    String olddest, AddressBook addressbook) {
        if (log != null) {
            StringBuilder buf = new StringBuilder(16);
            final int sz = dests.size();
            for (int i = 0; i < sz; i++) {
                buf.append(dests.get(i).toBase64().substring(0, 6));
                if (i != sz - 1)
                    buf.append(", ");
            }
            log.append("Action: " + action + " failed because" +
                       " destinations for " + name +
                       " (" + buf + ')' +
                       " do not include" +
                       " (" + olddest.substring(0, 6) + ')' +
                       ". From: " + addressbook.getLocation());
        }
    }

    /**
     * Run an update, using the Map settings to provide the parameters.
     * 
     * @param settings
     *            A Map containg the parameters needed by update.
     * @param home
     *            The directory containing addressbook's configuration files.
     */
    public static void update(Map<String, String> settings, String home) {
        File published = null;
        boolean should_publish = Boolean.parseBoolean(settings.get("should_publish"));
        if (should_publish) 
            published = new File(home, settings.get("published_addressbook"));
        File subscriptionFile = new File(home, settings.get("subscriptions"));
        File logFile = new File(home, settings.get("log"));
        File etagsFile = new File(home, settings.get("etags"));
        File lastModifiedFile = new File(home, settings.get("last_modified"));
        File lastFetchedFile = new File(home, settings.get("last_fetched"));
        long delay;
        try {
            delay = Long.parseLong(settings.get("update_delay"));
        } catch (NumberFormatException nfe) {
            delay = 12;
        }
        delay *= 60 * 60 * 1000;
        
        List<String> defaultSubs = new ArrayList<String>(4);
        // defaultSubs.add("http://i2p/NF2RLVUxVulR3IqK0sGJR0dHQcGXAzwa6rEO4WAWYXOHw-DoZhKnlbf1nzHXwMEJoex5nFTyiNMqxJMWlY54cvU~UenZdkyQQeUSBZXyuSweflUXFqKN-y8xIoK2w9Ylq1k8IcrAFDsITyOzjUKoOPfVq34rKNDo7fYyis4kT5bAHy~2N1EVMs34pi2RFabATIOBk38Qhab57Umpa6yEoE~rbyR~suDRvD7gjBvBiIKFqhFueXsR2uSrPB-yzwAGofTXuklofK3DdKspciclTVzqbDjsk5UXfu2nTrC1agkhLyqlOfjhyqC~t1IXm-Vs2o7911k7KKLGjB4lmH508YJ7G9fLAUyjuB-wwwhejoWqvg7oWvqo4oIok8LG6ECR71C3dzCvIjY2QcrhoaazA9G4zcGMm6NKND-H4XY6tUWhpB~5GefB3YczOqMbHq4wi0O9MzBFrOJEOs3X4hwboKWANf7DT5PZKJZ5KorQPsYRSq0E3wSOsFCSsdVCKUGsAAAA/i2p/hosts.txt");
        defaultSubs.add(DEFAULT_SUB);
        
        SubscriptionList subscriptions = new SubscriptionList(subscriptionFile,
                                                              etagsFile, lastModifiedFile, lastFetchedFile,
                                                              delay, defaultSubs, settings.get("proxy_host"),
                                                              Integer.parseInt(settings.get("proxy_port")));
        Log log = SystemVersion.isAndroid() ? null : new Log(logFile);

        // If false, add hosts via naming service; if true, write hosts.txt file directly
        // Default false
        if (Boolean.parseBoolean(settings.get("update_direct"))) {
            // Direct hosts.txt access
            File routerFile = new File(home, settings.get("router_addressbook"));
            AddressBook master;
            if (should_publish) {
                File masterFile = new File(home, settings.get("master_addressbook"));
                master = new AddressBook(masterFile);
            } else {
                master = null;
            }
            AddressBook router = new AddressBook(routerFile);
            update(master, router, published, subscriptions, log);
        } else {
            // Naming service - no merging of master to router and published is supported.
            update(getNamingService(settings.get("naming_service")), published, subscriptions, log);
        }
    }

    /** depth-first search */
    private static NamingService searchNamingService(NamingService ns, String srch)
    {
        String name = ns.getName();
        if (name.equals(srch) || name.endsWith('/' + srch) || name.endsWith('\\' + srch))
            return ns;
        List<NamingService> list = ns.getNamingServices();
        if (list != null) {
            for (NamingService nss : list) {
                NamingService rv = searchNamingService(nss, srch);
                if (rv != null)
                    return rv;
            }
        }
        return null;                
    }

    /** @return the configured NamingService, or the root NamingService */
    private static NamingService getNamingService(String srch)
    {
        NamingService root = I2PAppContext.getGlobalContext().namingService();
        NamingService rv = searchNamingService(root, srch);
        return rv != null ? rv : root;                
    }

    /**
     * Load the settings, set the proxy, then enter into the main loop. The main
     * loop performs an immediate update, and then an update every number of
     * hours, as configured in the settings file.
     * 
     * @param args
     *            Command line arguments. If there are any arguments provided,
     *            the first is taken as addressbook's home directory, and the
     *            others are ignored.
     */
    public static void main(String[] args) {
        Daemon daemon = new Daemon();
        if (args != null && args.length > 0 && args[0].equals("test"))
            daemon.test(args);
        else
            daemon.run(args);
    }

    /** @since 0.9.26 */
    public static void test(String[] args) {
        Properties ctxProps = new Properties();
        String PROP_FORCE = "i2p.naming.blockfile.writeInAppContext";
        ctxProps.setProperty(PROP_FORCE, "true");
        I2PAppContext ctx = new I2PAppContext(ctxProps);
        NamingService ns = getNamingService("hosts.txt");
        File published = new File("test-published.txt");
        Log log = new Log(new File("test-log.txt"));
        SubscriptionList subscriptions = new SubscriptionList("test-sub.txt");
        update(ns, published, subscriptions, log);
        ctx.logManager().flush();
    }
    
    public void run(String[] args) {
        _running = true;
        String settingsLocation = "config.txt";
        File homeFile;
        if (args.length > 0) {
            homeFile = new SecureDirectory(args[0]);
            if (!homeFile.isAbsolute())
                homeFile = new SecureDirectory(I2PAppContext.getGlobalContext().getRouterDir(), args[0]);
        } else {
            homeFile = new SecureDirectory(System.getProperty("user.dir"));
        }
        
        Map<String, String> defaultSettings = new HashMap<String, String>();
        defaultSettings.put("proxy_host", "127.0.0.1");
        defaultSettings.put("proxy_port", "4444");
        defaultSettings.put("master_addressbook", "../userhosts.txt");
        defaultSettings.put("router_addressbook", "../hosts.txt");
        defaultSettings.put("published_addressbook", "../eepsite/docroot/hosts.txt");
        defaultSettings.put("should_publish", "false");
        defaultSettings.put("log", "log.txt");
        defaultSettings.put("subscriptions", "subscriptions.txt");
        defaultSettings.put("etags", "etags");
        defaultSettings.put("last_modified", "last_modified");
        defaultSettings.put("last_fetched", "last_fetched");
        defaultSettings.put("update_delay", "12");
        defaultSettings.put("update_direct", "false");
        defaultSettings.put("naming_service", "hosts.txt");
        
        if (!homeFile.exists()) {
            boolean created = homeFile.mkdirs();
            if (created)
                System.out.println("INFO:  Addressbook directory " + homeFile.getName() + " created");
            else
                System.out.println("ERROR: Addressbook directory " + homeFile.getName() + " could not be created");
        }
        
        File settingsFile = new File(homeFile, settingsLocation);
        
        Map<String, String> settings = ConfigParser.parse(settingsFile, defaultSettings);
        // wait
        try {
            Thread.sleep(5*60*1000 + I2PAppContext.getGlobalContext().random().nextLong(5*60*1000));
	    // Static method, and redundent Thread.currentThread().sleep(5*60*1000);
        } catch (InterruptedException ie) {}
        
        while (_running) {
            long delay = Long.parseLong(settings.get("update_delay"));
            if (delay < 1) {
                delay = 1;
            }
            
            update(settings, homeFile.getAbsolutePath());
            try {
                synchronized (this) {
                    wait(delay * 60 * 60 * 1000);
                }
            } catch (InterruptedException exp) {
            }
            if (!_running)
                break;
            settings = ConfigParser.parse(settingsFile, defaultSettings);
        }
    }
 
    /**
     * Call this to get the addressbook to reread its config and 
     * refetch its subscriptions.
     */
    public void wakeup() {
        synchronized (this) {
            notifyAll();
        }
    }

    public void stop() {
        _running = false;
        wakeup();
    }
}
