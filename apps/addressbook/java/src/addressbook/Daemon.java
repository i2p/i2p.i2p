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

import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.io.File;
import java.io.IOException;

/**
 * Main class of addressbook.  Performs updates, and runs the main loop.
 * 
 * @author Ragnarok
 *
 */
public class Daemon {

    /**
     * Update the router and published address books using remote data from the
     * subscribed address books listed in subscriptions.
     * 
     * @param master
     *            The master AddressBook. This address book is never
     *            overwritten, so it is safe for the user to write to.
     * @param router
     *            The router AddressBook. This is the address book read by
     *            client applications.
     * @param published
     *            The published AddressBook. This address book is published on
     *            the user's eepsite so that others may subscribe to it.
     * @param subscriptions
     *            A SubscriptionList listing the remote address books to update
     *            from.
     * @param log
     *            The log to write changes and conflicts to.
     */
    public static void update(AddressBook master, AddressBook router,
            File published, SubscriptionList subscriptions, Log log) {
        String routerLocation = router.getLocation();
        master.merge(router);
        Iterator iter = subscriptions.iterator();
        while (iter.hasNext()) {
            master.merge((AddressBook) iter.next(), log);
        }
        master.write(new File(routerLocation));
        master.write(published);
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
    public static void update(Map settings, String home) {
        File masterFile = new File(home, (String) settings
                .get("master_addressbook"));
        File routerFile = new File(home, (String) settings
                .get("router_addressbook"));
        File published = new File(home, (String) settings
                .get("published_addressbook"));
        File subscriptionFile = new File(home, (String) settings
                .get("subscriptions"));
        File logFile = new File(home, (String) settings.get("log"));
        File etagsFile = new File(home, (String) settings.get("etags"));
        File lastModifiedFile = new File(home, (String) settings
                .get("last_modified"));

        AddressBook master = new AddressBook(masterFile);
        AddressBook router = new AddressBook(routerFile);
        SubscriptionList subscriptions = new SubscriptionList(subscriptionFile,
                etagsFile, lastModifiedFile);
        Log log = new Log(logFile);

        Daemon.update(master, router, published, subscriptions, log);
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
        String settingsLocation = "config.txt";
        Map settings = new HashMap();
        String home;
        if (args.length > 0) {
            home = args[0];
        } else {
            home = ".";
        }
        try {
            settings = ConfigParser.parse(new File(home, settingsLocation));
        } catch (IOException exp) {
            System.out.println("Could not load " + settingsLocation);
        }

        System.setProperty("proxySet", "true");
        System.setProperty("http.proxyHost", (String) settings
                .get("proxy_host"));
        System.setProperty("http.proxyPort", (String) settings
                .get("proxy_port"));
        long delay = Long.parseLong((String) settings.get("update_delay"));
        if (delay < 1) {
            delay = 1;
        }
        while (true) {
            Daemon.update(settings, home);
            try {
                Thread.sleep(delay * 60 * 60 * 1000);
            } catch (InterruptedException exp) {
            }
        }
    }
}