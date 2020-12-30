package net.i2p.router.time;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.i2p.I2PAppContext;
import net.i2p.data.DataHelper;
import net.i2p.router.transport.GeoIP;

/**
 *  Country to continent mapping for NTP.
 *  @since 0.9.20
 */
class Zones {

    private final I2PAppContext _context;
    // can't log here, called from RouterClock constructor, stack overflow
    //private final Log _log;

    // lower case country to NTP region
    private final Map<String, String> _countryToZone;
    // upper case continent to NTP region
    private final Map<String, String> _continentToZone;

    private static final String CONTINENT_FILE_DEFAULT = "continents.txt";

    /**
     * ref: http://dev.maxmind.com/geoip/legacy/codes/country_continent/
     *  ref: http://www.pool.ntp.org/zone/@
     */
    private static final String[] ZONES = {
        // not an NTP zone
        //"--", "anonymous-proxy",
        "AF", "africa",
        // not an NTP zone
        //"AN", "antarctica",
        "AS", "asia",
        "EU", "europe",
        "NA", "north-america",
        "OC", "oceania",
        "SA", "south-america"
    };

    /**
     *  Reads in the file in the constructor,
     *  so hold onto this.
     */
    public Zones(I2PAppContext ctx) {
        _context = ctx;
        //_log = ctx.logManager().getLog(Zones.class);
        _countryToZone = new HashMap<String, String>(256);
        _continentToZone = new HashMap<String, String>(8);
        for (int i = 0; i < ZONES.length; i += 2) {
            _continentToZone.put(ZONES[i], ZONES[i+1]);
        }
        readContinentFile();
    }

    /**
     *  Get the NTP zone for a country
     *
     *  @param country non-null, two letter code, case-independent
     *  @return lower-case NTP zone, e.g. "africa", or null
     */
    public String getZone (String country) {
        return _countryToZone.get(country.toLowerCase(Locale.US));
    }

    /**
     *  Read in and parse the continent file.
     *  The file need not be sorted.
     *
     *  Format:
     *    #comment (# must be in column 1)
     *    country code,continent code
     *
     *  Example:
     *    US,NA
     *
     *  Modified from GeoIP.readCountryFile()
     *  ref: http://dev.maxmind.com/geoip/legacy/codes/country_continent/
     */
    private void readContinentFile() {
        InputStream is = Zones.class.getResourceAsStream("/net/i2p/router/util/resources/" + CONTINENT_FILE_DEFAULT);
        if (is == null) {
            System.out.println("Continent file not found");
            return;
        }
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line = null;
            while ((line = br.readLine()) != null) {
                try {
                    if (line.charAt(0) == '#')
                        continue;
                    String[] s = DataHelper.split(line, ",");
                    String ucContinent = s[1].toUpperCase(Locale.US).trim();
                    String zone = _continentToZone.get(ucContinent);
                    if (zone == null)
                        continue;
                    String lcCountry = s[0].toLowerCase(Locale.US).trim();
                    _countryToZone.put(lcCountry, zone);
                    //if (_log.shouldDebug())
                    //   _log.debug("Country " + lcCountry + " is in " + zone);
                } catch (IndexOutOfBoundsException ioobe) {}
            }
        } catch (IOException ioe) {
            System.out.println("Error reading the continent file");
        } finally {
            try { is.close(); } catch (IOException ioe) {}
            if (br != null) try { br.close(); } catch (IOException ioe) {}
        }
    }

/****
    public static void main(String args[]) {
        Zones z = new Zones(I2PAppContext.getGlobalContext());
        String tests[] = {"us", "US", "nz", "fr", "vU", "br", "cn", "ao", "A1", "foo" };
        for (int i = 0; i < tests.length; i++) {
            System.out.println(tests[i] + " : " + z.getZone(tests[i]));
        }
    }
****/
}
