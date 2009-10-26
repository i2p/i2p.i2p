package dummy;

/**
 *  Just more strings for xgettext, that don't appear in the source anywhere.
 *  I'm sure there's easier ways to do this, but this will do for now.
 *
 *  Obviously, do not compile this.
 */
class Dummy {
    void dummy {
        // wars
        _("addressbook");
        _("i2psnark");
        _("i2ptunnel");
        _("susimail");
        _("susidns");
        _("routerconsole");

        // clients, taken from clients.config
        // note that if the wording changes in clients.config, we have to
        // keep the old string here as well for existing installs
        _("Web console");
        _("SAM application bridge");
        _("Application tunnels");
        _("My eepsite web server");
        _("Browser launch at startup");
        _("BOB application bridge");
    }
}
