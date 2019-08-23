package net.i2p.router.transport;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 *  Maintain a list of countries that may have
 *  tight restrictions on applications like ours.
 *  @since 0.8.13
 */
public abstract class StrictCountries {

    private static final Set<String> _countries;

    // List updated using the Freedom in the World Index 2019
    // https://freedomhouse.org/report/countries-world-freedom-2019
    // General guidance: Include countries with a Civil Liberties (CL) score of 6 or 7.

    static {
        String[] c = {
            /* Afghanistan */ "AF",
            /* Azerbaijan */ "AZ",
            /* Bahrain */ "BH",
            /* Belarus */ "BY",
            /* Brunei */ "BN",
            /* Burundi */ "BI",
            /* Cameroon */ "CM",
            /* Central African Republic */ "CF",
            /* Chad */ "TD",
            /* China */ "CN",
            /* Cuba */ "CU",
            /* Democratic Republic of the Congo */ "CD",
            /* Egypt */ "EG",
            /* Equatorial Guinea */ "GQ",
            /* Eritrea */ "ER",
            /* Ethiopia */ "ET",
            /* Iran */ "IR",
            /* Kazakhstan */ "KZ",
            /* Laos */ "LA",
            /* Libya */ "LY",
            /* Myanmar */ "MM",
            /* North Korea */ "KP",
            /* Palestinian Territories */ "PS",
            /* Rwanda */ "RW",
            /* Saudi Arabia */ "SA",
            /* Somalia */ "SO",
            /* South Sudan */ "SS",
            /* Sudan */ "SD",
            /* Eswatini (Swaziland) */ "SZ",
            /* Syria */ "SY",
            /* Tajikistan */ "TJ",
            /* Thailand */ "TH",
            /* Turkey */ "TR",
            /* Turkmenistan */ "TM",
            /* Venezuela */ "VE",
            /* United Arab Emirates */ "AE",
            /* Uzbekistan */ "UZ",
            /* Western Sahara */ "EH",
            /* Yemen */ "YE"
        };
        _countries = new HashSet<String>(Arrays.asList(c));
    }

    /** @param country non-null, two letter code, case-independent */
    public static boolean contains(String country) {
        return _countries.contains(country.toUpperCase(Locale.US));
    }
}
