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
        _t("addressbook");
        _t("i2psnark");
        _t("i2ptunnel");
        _t("susimail");
        _t("susidns");
        _t("routerconsole");

        // clients, taken from clients.config, for ConfigClientsHelper
        // note that if the wording changes in clients.config, we have to
        // keep the old string here as well for existing installs
        _t("Web console");
        _t("SAM application bridge");
        _t("Application tunnels");
        _t("My eepsite web server");
        _t("I2P webserver (eepsite)");        
        _t("Browser launch at startup");
        _t("BOB application bridge");
        _t("I2P Router Console");
        _t("Open Router Console in web browser at startup");        
        
        // tunnel nicknames, taken from i2ptunnel.config so they will display
        // nicely under 'local destinations' in the summary bar
        // note that if the wording changes in i2ptunnel.config, we have to
        // keep the old string here as well for existing installs
        _t("shared clients");
        _t("IRC proxy");
        _t("eepsite");
        _t("I2P webserver");
        _t("HTTP Proxy");        
        // hardcoded in i2psnark
        _t("I2PSnark");


        // standard themes for ConfigUIHelper
        _t("dark");
        _t("light");

        // stat groups for stats.jsp
        // See StatsGenerator for groups mapped to a display name
        _t("Bandwidth");
        _t("Encryption");
        _t("Peers");
        _t("Router");
        _t("Stream");
        _t("Transport");
        _t("Tunnels");

        // parameters in transport addresses (netdb.jsp)
        // may or may not be worth translating
        _t("host");
        _t("key");
        _t("port");
    }
}
