package dummy;

/**
 *  Just more strings for xgettext, that don't appear in the source anywhere.
 *  I'm sure there's easier ways to do this, but this will do for now.
 *
 *  Obviously, do not compile this.
 */
class Dummy {
    void dummy {
        // wars for ConfigClientsHelper
        _("addressbook");
        _("i2psnark");
        _("i2ptunnel");
        _("susimail");
        _("susidns");
        _("routerconsole");

        // clients, taken from clients.config, for ConfigClientsHelper
        // note that if the wording changes in clients.config, we have to
        // keep the old string here as well for existing installs
        _("Web console");
        _("SAM application bridge");
        _("Application tunnels");
        _("My eepsite web server");
        _("I2P webserver (eepsite)");        
        _("Browser launch at startup");
        _("BOB application bridge");
        _("I2P Router Console");
        _("Open Router Console in web browser at startup");        
        
        // tunnel nicknames, taken from i2ptunnel.config so they will display
        // nicely under 'local destinations' in the summary bar
        // note that if the wording changes in i2ptunnel.config, we have to
        // keep the old string here as well for existing installs
        _("shared clients");
        _("IRC proxy");
        _("eepsite");
        _("I2P webserver");
        _("HTTP Proxy");        
        // older names for pre-0.7.4 installs
        _("eepProxy");
        _("ircProxy");
        // hardcoded in i2psnark
        _("I2PSnark");
        // hardcoded in iMule?
        _("iMule");


        // standard themes for ConfigUIHelper
        _("classic");
        _("dark");
        _("light");
        _("midnight");        

        // stat groups for stats.jsp
        _("Bandwidth");
        _("BandwidthLimiter");
        _("ClientMessages");
        _("Encryption");
        _("i2cp");
        _("I2PTunnel");
        _("InNetPool");
        _("JobQueue");
        _("NetworkDatabase");
        _("ntcp");
        _("Peers");
        _("Router");
        _("Stream");
        _("Throttle");
        _("Transport");
        _("Tunnels");
        _("udp");

        // parameters in transport addresses (netdb.jsp)
        // may or may not be worth translating
        _("host");
        _("key");
        _("port");
        // capabilities
        _("caps");
        // introducer host
        _("ihost0");
        _("ihost1");
        _("ihost2");
        // introducer port
        _("iport0");
        _("iport1");
        _("iport2");
        // introducer key
        _("ikey0");
        _("ikey1");
        _("ikey2");
        // introducer tag
        _("itag0");
        _("itag1");
        _("itag2");
    }
}
