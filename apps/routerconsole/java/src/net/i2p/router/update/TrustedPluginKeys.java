package net.i2p.router.update;

import java.util.HashMap;
import java.util.Map;

/**
 *  Plugin keys we know about.
 *  Contact I2P devs to be added to this list.
 *
 *  @since 0.9.14.1
 */
class TrustedPluginKeys {

    private static final String[] KEYS = {

        "sponge-plugin@mail.i2p",
        "BS8TvZgt7WNkbwCXtwMFsrRALSxjP4P7V5Yl3CATT9VBWjF4UtnDZ4JTQYPbEjCI~3cwPI6tk6X72jRr5ZNJy0v2EA9Kf2QS05O6GjqiXANLqZfXDDjmjPxdaaEJ2L~U36gEbQ8rfeHCKny4tKlLpCIzpNK3psWEt~erBPM1rBA=",

        "volvagia@mail.i2p",
        "ksgA0OByutKGRquqc4H6kR~VNrpq2t5JllEvUfHt~Qp1mcsyT3UfP8fXFPPakN9RDkjibfVngx9LnD6KHz72O21JK0tKquKydtaDtCsXw1EZxPEzsIfsy3mJAwtmoMo0mXIReCadl4MgPz5xVjd68i2qdN03i9shxpn0BF68dhY=",

        "dev@robertfoss.se",
        "kAOpTC~9jfieoQBFfgQM-Krb2iYYjXsPH1vsWZPqsYDeaRX8~mQotm3M5duYBVa17A1usYodlZbEB2Q2CW0Aub7EiYBYsz1AQYlFrH47Yfl440kbo8Uwjf-SULW7LW9L5rBV~yzDHHmPjuob743o52CKAIM3KDZKNE7eGlLAuOI=",

        "killyourtv@mail.i2p",
        "DAVvT6zMcRuzJi3V8DKKV6o0GjXoQsEwnJsFMaVG1Se-KPQjfP8PbgKJDcrFe0zNJfh3yPdsocA~A~s9U6pvimlCXH2pnJGlNNojtFCZC3DleROl5-4EkYw~UKAg940o5yg1OCBVlRZBSrRAQIIjFGkxxPQc12dA~cfpryNk7Dc=",

        "chisquare@mail.i2p",
        "MT2mCbqCv2F3m9kXFsk3TKKZJ0xOHcYQ6hBzDqdOPtn2pbjwjve0~4R0uMhMCOvHbQoGRE6W2O~EJbhkIK5vlFQz~OtlzXTZ20N5oqt8KYt9RpbfSxT0LzY23sZd5dAMre9T7xbgrPzp2s94i2WOLK7lBBt59CWlcao194WfWA8=",

        "bitbuck3t@mail.i2p",
        "mLLpV1sH1CSgultEHI7TGE6uA0vjl-DYFuhdIPFWTNUTxkXq3U4YogYQmNlOwMcl9bqDHzcMelxyvARe-EQSTlZ846DUfzRqI3B3ANJDVbQr4RIJDkqvp1oKf57B7mZpIoqc6QJC0PG7cpShWBXsTknG1u7lKgjIKhbHDQaOqz0=",

        "HungryHobo@mail.i2p",
        "l3G6um9nB9EDLkT9cUusz5fX-GxXSWE5zaj2~V8lUL~XsGuFf8gKqzJLKNkAw0CgDIDsLRHHuUaF7ZHo5Z7HG~9JJU9Il4G2jyNYtg5S8AzG0UxkEt-JeBEqIxv5GDn6OFKr~wTI0UafJbegEWokl-8m-GPWf0vW-yPMjL7y5MI=",

        "zzz-plugin@mail.i2p",
        "Z3xbCcZiIA44W65~q4u5Rm9ZZWvBIv1bCvTx8DrbsKefu0PZ1134xzkI~vyXuRmmujvSwTVTfgEnxL81hwmpuB4aXMBLDlBmckspFnGKte~HefYI-6WcK79rnZPvNQCffdgi~EgWnUMYDR20PBWQKaGwajkSb-LOK~l2Z69G6aI="

    };

    /**
     *  @return map of B64 DSA keys to signer names
     */
    public static Map<String, String> getKeys() {
        Map<String, String> rv = new HashMap<String, String>(KEYS.length / 2);
        for (int i = 0; i < KEYS.length; i += 2) {
            rv.put(KEYS[i+1], KEYS[i]);
        }
        return rv;
    }
}
