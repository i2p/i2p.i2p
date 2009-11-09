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
        _("Browser launch at startup");
        _("BOB application bridge");

        // tunnel nicknames, taken from i2ptunnel.config so they will display
        // nicely under 'local destinations' in the summary bar
        // note that if the wording changes in i2ptunnel.config, we have to
        // keep the old string here as well for existing installs
        _("shared clients");
        _("IRC proxy");
        _("eepsite");
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
    }
}
