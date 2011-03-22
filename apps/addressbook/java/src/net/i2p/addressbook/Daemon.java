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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.i2p.I2PAppContext;
import net.i2p.client.naming.NamingService;
import net.i2p.client.naming.SingleFileNamingService;
import net.i2p.data.DataFormatException;
import net.i2p.data.Destination;
import net.i2p.util.SecureDirectory;

/**
 * Main class of addressbook.  Performs updates, and runs the main loop.
 * 
 * @author Ragnarok
 *
 */
public class Daemon {
    public static final String VERSION = "2.0.4";
    private static final Daemon _instance = new Daemon();
    private boolean _running;
    private static final boolean DEBUG = true;
    
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
        Iterator<AddressBook> iter = subscriptions.iterator();
        while (iter.hasNext()) {
            // yes, the EepGet fetch() is done in next()
            router.merge(iter.next(), false, log);
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
     * @since 0.8.6
     */
    public static void update(NamingService router, File published, SubscriptionList subscriptions, Log log) {
        // If the NamingService is a database, we look up as we go.
        // If it is a text file, we do things differently, to avoid O(n**2) behavior
        // when scanning large subscription results (i.e. those that return the whole file, not just the new entries) -
        // we load all the known hostnames into a Set one time.
        String nsClass = router.getClass().getSimpleName();
        boolean isTextFile = nsClass.equals("HostsTxtNamingService") || nsClass.equals("SingleFileNamingService");
        Set<String> knownNames = null;

        NamingService publishedNS = null;
        Iterator<AddressBook> iter = subscriptions.iterator();
        while (iter.hasNext()) {
            // yes, the EepGet fetch() is done in next()
            long start = System.currentTimeMillis();
            AddressBook sub = iter.next();
            long end = System.currentTimeMillis();
            if (DEBUG && log != null)
                log.append("Fetch of " + sub.getLocation() + " took " + (end - start));
            start = end;
            int old = 0, nnew = 0, invalid = 0, conflict = 0;
            for (Iterator<Map.Entry<String, String>> eIter = sub.iterator(); eIter.hasNext(); ) {
                Map.Entry<String, String> entry = eIter.next();
                String key = entry.getKey();
                boolean isKnown;
                Destination oldDest = null;
                if (isTextFile) {
                    if (knownNames == null) {
                        // load the hostname set
                        Properties opts = new Properties();
                        opts.setProperty("file", "hosts.txt");
                        knownNames = router.getNames(opts);
                    }
                    isKnown = knownNames.contains(key);
                } else {
                    oldDest = router.lookup(key);
                    isKnown = oldDest != null;
                }
                try {
                    if (!isKnown) {
                        if (AddressBook.isValidKey(key)) {
                            Destination dest = new Destination(entry.getValue());
                            boolean success = router.put(key, dest);
                            if (log != null) {
                                if (success)
                                    log.append("New address " + key +
                                               " added to address book. From: " + sub.getLocation());
                                else
                                    log.append("Save to naming service " + router + " failed for new key " + key);
                            }
                            // now update the published addressbook
                            if (published != null) {
                                if (publishedNS == null)
                                    publishedNS = new SingleFileNamingService(I2PAppContext.getGlobalContext(), published.getAbsolutePath());
                                success = publishedNS.putIfAbsent(key, dest);
                                if (!success)
                                    log.append("Save to published addressbook " + published.getAbsolutePath() + " failed for new key " + key);
                            }
                            if (isTextFile)
                                // keep track for later dup check
                                knownNames.add(key);
                            nnew++;
                        } else if (log != null) {
                            log.append("Bad hostname " + key + " from "
                                   + sub.getLocation());
                            invalid++;
                        }        
                    } else if (false && DEBUG && log != null) {
                        // lookup the conflict if we haven't yet (O(n**2) for text file)
                        if (isTextFile)
                            oldDest = router.lookup(key);
                        if (oldDest != null && !oldDest.toBase64().equals(entry.getValue())) {
                            log.append("Conflict for " + key + " from "
                                       + sub.getLocation()
                                       + ". Destination in remote address book is "
                                       + entry.getValue());
                            conflict++;
                        } else {
                            old++;
                        }
                    } else {
                        old++;
                    }
                } catch (DataFormatException dfe) {
                    if (log != null)
                        log.append("Invalid b64 for" + key + " From: " + sub.getLocation());
                    invalid++;
                }
            }
            if (DEBUG && log != null) {
                log.append("Merge of " + sub.getLocation() + " into " + router +
                           " took " + (System.currentTimeMillis() - start) + " ms with " +
                           nnew + " new, " +
                           old + " old, " +
                           invalid + " invalid, " +
                           conflict + " conflicts");
            }
            sub.delete();
        }
        subscriptions.write();
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
        boolean should_publish = Boolean.valueOf(settings.get("should_publish")).booleanValue();
        if (should_publish) 
            published = new File(home, settings
                .get("published_addressbook"));
        File subscriptionFile = new File(home, settings
                .get("subscriptions"));
        File logFile = new File(home, settings.get("log"));
        File etagsFile = new File(home, settings.get("etags"));
        File lastModifiedFile = new File(home, settings
                .get("last_modified"));
        File lastFetchedFile = new File(home, settings
                .get("last_fetched"));
        long delay;
        try {
            delay = Long.parseLong(settings.get("update_delay"));
        } catch (NumberFormatException nfe) {
            delay = 12;
        }
        delay *= 60 * 60 * 1000;
        
        List<String> defaultSubs = new LinkedList();
        // defaultSubs.add("http://i2p/NF2RLVUxVulR3IqK0sGJR0dHQcGXAzwa6rEO4WAWYXOHw-DoZhKnlbf1nzHXwMEJoex5nFTyiNMqxJMWlY54cvU~UenZdkyQQeUSBZXyuSweflUXFqKN-y8xIoK2w9Ylq1k8IcrAFDsITyOzjUKoOPfVq34rKNDo7fYyis4kT5bAHy~2N1EVMs34pi2RFabATIOBk38Qhab57Umpa6yEoE~rbyR~suDRvD7gjBvBiIKFqhFueXsR2uSrPB-yzwAGofTXuklofK3DdKspciclTVzqbDjsk5UXfu2nTrC1agkhLyqlOfjhyqC~t1IXm-Vs2o7911k7KKLGjB4lmH508YJ7G9fLAUyjuB-wwwhejoWqvg7oWvqo4oIok8LG6ECR71C3dzCvIjY2QcrhoaazA9G4zcGMm6NKND-H4XY6tUWhpB~5GefB3YczOqMbHq4wi0O9MzBFrOJEOs3X4hwboKWANf7DT5PZKJZ5KorQPsYRSq0E3wSOsFCSsdVCKUGsAAAA/i2p/hosts.txt");
        defaultSubs.add("http://www.i2p2.i2p/hosts.txt");
        
        SubscriptionList subscriptions = new SubscriptionList(subscriptionFile,
                etagsFile, lastModifiedFile, lastFetchedFile, delay, defaultSubs, settings
                .get("proxy_host"), Integer.parseInt(settings.get("proxy_port")));
        Log log = new Log(logFile);

        // If false, add hosts via naming service; if true, write hosts.txt file directly
        // Default false
        if (Boolean.valueOf(settings.get("update_direct")).booleanValue()) {
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
        _instance.run(args);
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
        
        Map<String, String> defaultSettings = new HashMap();
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
    public static void wakeup() {
        synchronized (_instance) {
            _instance.notifyAll();
        }
    }

    public static void stop() {
        _instance._running = false;
        wakeup();
    }
}
