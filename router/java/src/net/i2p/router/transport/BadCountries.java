package net.i2p.router.transport;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 *  Maintain a list of bad places.
 *  @since 0.8.13
 */
abstract class BadCountries {

    private static final Set<String> _countries;

    // zzz.i2p/topics/969
    // List created based on the Press Freedom Index. Those countries with a score of higher than 50 are included:
    // http://en.wikipedia.org/wiki/Press_Freedom_Index
    // Except:
    // I don't really think that is usage of I2P is dangerous in countries from CIS
    // General situation is really bad (like in Russia) but people here doesn't have problems with Ecnryption usage.

    static {
        String[] c = {
            /* Afghanistan */ "AF",
            /* Bahrain */ "BH",
            /* Brunei */ "BN",
            /* Burma */ "MM",
            /* China */ "CN",
            /* Colombia */ "CO",
            /* Cuba */ "CU",
            /* Democratic Republic of the Congo */ "CD",
            /* Equatorial Guinea */ "GQ",
            /* Eritrea */ "ER",
            /* Fiji */ "FJ",
            /* Honduras */ "HN",
            /* Iran */ "IR",
            /* Laos */ "LA",
            /* Libya */ "LY",
            /* Malaysia */ "MY",
            /* Nigeria */ "NG",
            /* North Korea */ "KP",
            /* Pakistan */ "PK",
            /* Palestinian Territories */ "PS",
            /* Philippines */ "PH",
            /* Rwanda */ "RW",
            /* Saudi Arabia */ "SA",
            /* Somalia */ "SO",
            /* Sri Lanka */ "LK",
            /* Sudan */ "SD",
            /* Swaziland */ "SZ",
            /* Syria */ "SY",
            /* Thailand */ "TH",
            /* Tunisia */ "TN",
            /* Vietnam */ "VN",
            /* Yemen */ "YE"
        };
        _countries = new HashSet(Arrays.asList(c));
    }

    /** @param country non-null, two letter code, case-independent */
    public static boolean contains(String country) {
        return _countries.contains(country.toUpperCase(Locale.US));
    }
}
