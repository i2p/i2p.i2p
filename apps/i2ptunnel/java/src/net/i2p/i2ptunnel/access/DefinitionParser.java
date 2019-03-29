package net.i2p.i2ptunnel.access;

import java.util.List;
import java.util.ArrayList;

import java.io.File;

import net.i2p.data.DataHelper;

/**
 * Utility class for parsing filter definitions
 * 
 * @since 0.9.40
 */
class DefinitionParser {

    private static enum Rule { DEFAULT, EXPLICIT, FILE, RECORDER }

    /**
     * <p>
     * Processes an array of String objects containing the human-readable definition of
     * the filter.
     * </p>
     * <p>
     * The definition of a filter is a list of Strings.  Each line can represent one of 
     * these items:
     * </p>
     * <ul>
     * <li>definition of a default threshold to apply to any remote destinations not
     *   listed in this file or any of the referenced files</li>
     * <li>definition of a threshold to apply to a specific remote destination</li>
     * <li>definition of a threshold to apply to remote destinations listed in a file</li>
     * <li>definition of a threshold that if breached will cause the offending remote
     *   destination to be recorded in a specified file</li>
     * </ul>
     * <p>
     * The order of the definitions matters.  The first threshold for a given destination
     * (whether explicit or listed in a file) overrides any future thresholds for the
     * same destination, whether explicit or listed in a file.
     * </p>
     * <p>
     * Thresholds:
     * </p>
     * <p>
     * A threshold is defined by the number of connection attempts a remote destination is
     * permitted to perform over a specified number of minutes before a "breach" occurs.
     * For example the following threshold definition "15/5" means that the same remote
     * destination is allowed to make 14 connection attempts over a 5 minute period,  If
     * it makes one more attempt within the same period, the threshold will be breached.
     * </p>
     * <p>
     * The threshold format can be one of the following:
     * </p>
     * <ul>
     * <li>Numeric definition of number of connections over number minutes - "15/5", 
     *   "30/60", and so on.  Note that if the number of connections is 1 (as for 
     *   example in "1/1") the first connection attempt will result in a breach.</li>
     * <li>The word "allow".  This threshold is never breached, i.e. infinite number of 
     *   connection attempts is permitted.</li>
     * <li>The word "deny".  This threshold is always breached, i.e. no connection attempts
     *   will be allowed.</li>
     * </ul>
     *
     * The default threshold applies to any remote destinations that are not explicitly
     * listed in the definition or in any of the referenced files.  To set a default 
     * threshold use the keyword "default".  The following are examples of default thresholds:
     * 
     * <pre>
     * 15/5 default
     * allow default
     * deny default
     * </pre>
     *
     * Explicit thresholds are applied to a remote destination listed in the definition itself.
     * Examples:
     * 
     * <pre>
     * 15/5 explicit asdfasdfasdf.b32.i2p
     * allow explicit fdsafdsafdsa.b32.i2p
     * deny explicit qwerqwerqwer.b32.i2p
     * </pre>
     *
     * For convenience it is possible to maintain a list of destinations in a file and define
     * a threshold for all of them in bulk.  Examples:
     *
     * <pre>
     * 15/5 file /path/throttled_destinations.txt
     * deny file /path/forbidden_destinations.txt
     * allow file /path/unlimited_destinations.txt
     * </pre>
     *
     * Recorders keep track of connection attempts made by a remote destination, and if that
     * breaches a certain threshold, that destination gets recorded in a given file.  Examples:
     *
     * <pre>
     * 30/5 recorder /path/aggressive.txt
     * 60/5 recorder /path/very_aggressive.txt
     * </pre>
     * <p>
     * It is possible to use a recorder to record aggressive destinations to a given file,
     * and then use that same file to throttle them.  For example, the following snippet will
     * define a filter that initially allows all connection attempts, but if any single
     * destination exceeds 30 attempts per 5 minutes it gets throttled down to 15 attempts per 
     * 5 minutes:
     * </p>
     * <pre>
     * # by default there are no limits
     * allow default
     * # but record overly aggressive destinations
     * 30/5 recorder /path/throttled.txt
     * # and any that end up in that file will get throttled in the future
     * 15/5 file /path/throttled.txt
     * </pre>
     * <p>
     * It is possible to use a recorder in one tunnel that writes to a file that throttles 
     * another tunnel.  It is possible to reuse the same file with destinations in multiple
     * tunnels.  And of course, it is possible to edit these files by hand.
     * </p>
     * <p>
     * Here is an example filter definition that applies some throttling by default, no throttling
     * for destinations in the file "friends.txt", forbids any connections from destinations
     * in the file "enemies.txt" and records any aggressive behavior in a file called
     * "suspicious.txt":
     * </p>
     * <pre>
     * 15/5 default
     * allow file /path/friends.txt
     * deny file /path/enemies.txt
     * 60/5 recorder /path/suspicious.txt
     * </pre>
     *
     * @return a FilterDefinition POJO representation for internal use
     * @throws InvalidDefinitionException if the definition is malformed
     */
    static FilterDefinition parse(String []definition) throws InvalidDefinitionException {
        
        DefinitionBuilder builder = new DefinitionBuilder();

        for (String line : definition) {
            String [] split = DataHelper.split(line,"[ \t]");
            split[0] = split[0].toLowerCase();
            
            Threshold threshold = parseThreshold(split[0]);
            Rule rule = parseRule(split[1]);

            File file;
            switch(rule) {
            case DEFAULT:
                builder.setDefaultThreshold(threshold);
                break;
            case EXPLICIT:
                builder.addElement(new ExplicitFilterDefinitionElement(split[2], threshold));
                break;
            case FILE:
                file = parseFileName(line, split);
                builder.addElement(new FileFilterDefinitionElement(file, threshold));
                break;
            case RECORDER: 
                file = parseFileName(line, split);
                builder.addRecorder(new Recorder(file, threshold));
            }
        }

        return builder.build();
    }

    private static Threshold parseThreshold(String s) throws InvalidDefinitionException {
        if ("allow".equals(s))
            return Threshold.ALLOW;
        if ("deny".equals(s))
            return Threshold.DENY;

        String [] split = DataHelper.split(s,"/");
        if (split.length != 2)
            throw new InvalidDefinitionException("Invalid threshold " + s);

        try {
            int connections = Integer.parseInt(split[0]);
            int minutes = Integer.parseInt(split[1]);
            if (connections < 0)
                throw new InvalidDefinitionException("Number of connections cannot be negative " + s);
            if (minutes < 1)
                throw new InvalidDefinitionException("Number of minutes must be at least 1 " + s);
            return new Threshold(connections, minutes);
        } catch (NumberFormatException bad) {
            throw new InvalidDefinitionException("Invalid threshold", bad);
        }
    }

    private static Rule parseRule(String s) throws InvalidDefinitionException {
        if ("default".equals(s))
            return Rule.DEFAULT;
        if ("explicit".equals(s))
            return Rule.EXPLICIT;
        if ("file".equals(s))
            return Rule.FILE;
        if ("recorder".equals(s))
            return Rule.RECORDER;

        throw new InvalidDefinitionException("unknown rule "+s);
    }

    private static File parseFileName(String s, String[] split) throws InvalidDefinitionException {
        if (split.length < 3)
            throw new InvalidDefinitionException("invalid definition "+s);
        int beginIndex = s.indexOf(split[1]);
        if (beginIndex < 0)
            throw new IllegalStateException("shouldn't have gotten here "+s);
        return new File(s.substring(beginIndex + split[1].length()).trim());
    }


    private static class DefinitionBuilder {
        private Threshold threshold;
        private List<FilterDefinitionElement> elements = new ArrayList<FilterDefinitionElement>();
        private List<Recorder> recorders = new ArrayList<Recorder>();

        void setDefaultThreshold(Threshold threshold) throws InvalidDefinitionException {
            if (this.threshold != null)
                throw new InvalidDefinitionException("default already set!");
            this.threshold = threshold;
        }

        void addElement(FilterDefinitionElement element) {
            elements.add(element);
        }

        void addRecorder(Recorder recorder) {
            recorders.add(recorder);
        }

        FilterDefinition build() {
            if (threshold == null)
                threshold = Threshold.ALLOW;

            FilterDefinitionElement [] elArray = new FilterDefinitionElement[elements.size()];
            elArray = elements.toArray(elArray);

            Recorder [] rArray = new Recorder[recorders.size()];
            rArray = recorders.toArray(rArray);

            return new FilterDefinition(threshold, elArray, rArray);
        }
    }
}
