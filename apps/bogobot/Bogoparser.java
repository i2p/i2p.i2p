/*
 * bogoparser - A simple logfile analyzer for bogobot.
 * 
 * Bogoparser.java
 * 2004 The I2P Project
 * This code is public domain.
 */

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author hypercubus
 * @version 0.4
 */
public class Bogoparser {

    private static void displayUsageAndExit() {
        System.out.println("\r\nUsage:\r\n\r\n    java Bogoparser [--by-duration] <logfile>\r\n");
        System.exit(1);
    }

    public static void main(String[] args) {

        Bogoparser bogoparser;

        if (args.length < 1 || args.length > 2)
            displayUsageAndExit();

        if (args.length == 2) {
            if (!args[0].equals("--by-duration"))
                displayUsageAndExit();
            bogoparser = new Bogoparser(args[1], true);
        }

        if (args.length == 1)
            bogoparser = new Bogoparser(args[0], false);
    }

    private Bogoparser(String logfile, boolean sortByDuration) {

        ArrayList sortedSessions;

        if (sortByDuration) {
            sortedSessions = sortSessionsByDuration(calculateSessionDurations(sortSessionsByTime(readLogfile(logfile))));
            formatAndOutputByDuration(sortedSessions);
        } else {
            sortedSessions = calculateSessionDurations(sortSessionsByQuitReason(sortSessionsByNick(sortSessionsByTime(readLogfile(logfile)))));
            formatAndOutput(sortedSessions);
        }
    }

    private ArrayList calculateSessionDurations(ArrayList sortedSessionsByQuitReasonOrDuration) {

        ArrayList calculatedSessionDurations = new ArrayList();

        for (int i = 0; i+1 < sortedSessionsByQuitReasonOrDuration.size(); i += 2) {

            String   joinsEntry       = (String) sortedSessionsByQuitReasonOrDuration.get(i);
            String[] joinsEntryFields = joinsEntry.split(" ");

            String  quitsEntry = (String) sortedSessionsByQuitReasonOrDuration.get(i+1);
            Pattern p          = Pattern.compile("^([^ ]+) [^ ]+ ([^ ]+) (.*)$");
            Matcher m          = p.matcher(quitsEntry);

            if (m.matches()) {

                String currentJoinTime             = joinsEntryFields[0];
                String currentNick                 = m.group(2);
                String currentQuitReason           = m.group(3);
                String currentQuitTime             = m.group(1);
                long   joinsTimeInMilliseconds;
                long   quitsTimeInMilliseconds;
                long   sessionLengthInMilliseconds;
                
                joinsTimeInMilliseconds = Long.parseLong(currentJoinTime);
                quitsTimeInMilliseconds = Long.parseLong(currentQuitTime);
                sessionLengthInMilliseconds = quitsTimeInMilliseconds - joinsTimeInMilliseconds;
                
                String hours   = "" + sessionLengthInMilliseconds/1000/60/60;
                String minutes = "" + (sessionLengthInMilliseconds/1000/60)%60;

                if (hours.length() < 2)
                    hours = "0" + hours;

                if (hours.length() < 3)
                    hours = "0" + hours;

                if (minutes.length() < 2)
                    minutes = "0" + minutes;

                int    columnPadding       = 19-currentNick.length();
                String columnPaddingString = " ";

                for (int j = 0; j < columnPadding; j++)
                    columnPaddingString = columnPaddingString + " ";

                calculatedSessionDurations.add(sessionLengthInMilliseconds + " " + currentNick + columnPaddingString + " online " + hours + " hours " + minutes + " minutes " + currentQuitReason);
            } else {
                System.out.println("\r\nError: Unexpected entry in logfile: " + quitsEntry);
                System.exit(1);
            }
        }
        return calculatedSessionDurations;
    }

    private void formatAndOutput(ArrayList sortedSessions) {

        String quitReason = null;

        for (int i = 0; i < sortedSessions.size(); i++) {

            String  entry = (String) sortedSessions.get(i);
            Pattern p     = Pattern.compile("^[\\d]+ ([^ ]+ +online [\\d]+ hours [\\d]+ minutes) (.*)$");
            Matcher m     = p.matcher(entry);

            if (m.matches()) {

                if (quitReason == null) {
                    quitReason = m.group(2);
                    System.out.println("\r\nQUIT: " + ((m.group(2).equals("")) ? "No Reason Given" : quitReason) + "\r\n");
                }

                String tempQuitReason = m.group(2);
                String tempSession    = m.group(1);

                if (tempQuitReason.equals(quitReason)) {
                    System.out.println("    " + tempSession);
                } else {
                    quitReason = null;
                    i -= 1;
                    continue;
                }
            } else {
                System.out.println("\r\nError: Unexpected entry in logfile: " + entry);
                System.exit(1);
            }
        }
        System.out.println("\r\n");
    }

    private void formatAndOutputByDuration(ArrayList sortedSessions) {
        System.out.println("\r\n");

        for (int i = 0; i < sortedSessions.size(); i++) {
            String[] columns = ((String) sortedSessions.get(i)).split(" ", 2);
            System.out.println(columns[1]);
        }

        System.out.println("\r\n");
    }

    private ArrayList readLogfile(String logfile) {

        ArrayList log = new ArrayList();

        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(logfile)));

            for (String line; (line = in.readLine()) != null; )
                log.add(line);

            in.close();

        } catch (FileNotFoundException e) {
            System.out.println("\r\nError: Can't find logfile '" + logfile + "'.\r\n");
            System.exit(1);

        } catch (IOException e) {
            System.out.println("\r\nError: Can't read logfile '" + logfile + "'.\r\n");
            System.exit(1);
        }
        return log;
    }

    /*
     * Performs an odd-even transposition sort.
     */
    private ArrayList sortSessionsByDuration(ArrayList calculatedSessionDurations) {

        for (int i = 0; i < calculatedSessionDurations.size()/2; i++) {
            for (int j = 0; j+1 < calculatedSessionDurations.size(); j += 2) {

                String[] currentDurationString = ((String) calculatedSessionDurations.get(j)).split(" ", 2);
                long     currentDuration       = Long.parseLong(currentDurationString[0]);
                String[] nextDurationString    = ((String) calculatedSessionDurations.get(j+1)).split(" ", 2);
                long     nextDuration          = Long.parseLong(nextDurationString[0]);

                if (currentDuration > nextDuration) {
                    calculatedSessionDurations.add(j, calculatedSessionDurations.get(j+1));
                    calculatedSessionDurations.remove(j+2);
                }
            }

            for (int j = 1; j+1 < calculatedSessionDurations.size(); j += 2) {

                String[] currentDurationString = ((String) calculatedSessionDurations.get(j)).split(" ", 2);
                long     currentDuration       = Long.parseLong(currentDurationString[0]);
                String[] nextDurationString    = ((String) calculatedSessionDurations.get(j+1)).split(" ", 2);
                long     nextDuration          = Long.parseLong(nextDurationString[0]);

                if (currentDuration > nextDuration) {
                    calculatedSessionDurations.add(j, calculatedSessionDurations.get(j+1));
                    calculatedSessionDurations.remove(j+2);
                }
            }
        }
        return calculatedSessionDurations;
    }

    private ArrayList sortSessionsByNick(ArrayList sortedSessionsByTime) {

        ArrayList sortedSessionsByNick = new ArrayList();

        while (sortedSessionsByTime.size() != 0) {

            String   entry       = (String) sortedSessionsByTime.get(0);
            String[] entryFields = entry.split(" ");
            String   currentNick = entryFields[2];

            sortedSessionsByNick.add(entry);
            sortedSessionsByNick.add(sortedSessionsByTime.get(1));
            sortedSessionsByTime.remove(0);
            sortedSessionsByTime.remove(0);
            for (int i = 0; i+1 < sortedSessionsByTime.size(); i += 2) {

                String   nextEntry       = (String) sortedSessionsByTime.get(i);
                String[] nextEntryFields = nextEntry.split(" ");

                if (nextEntryFields[2].equals(currentNick)) {
                    sortedSessionsByNick.add(nextEntry);
                    sortedSessionsByNick.add(sortedSessionsByTime.get(i+1));
                    sortedSessionsByTime.remove(i);
                    sortedSessionsByTime.remove(i);
                    i -= 2;
                }
            }
        }
        return sortedSessionsByNick;
    }

    private ArrayList sortSessionsByQuitReason(ArrayList sortedSessionsByNick) {

        ArrayList sortedSessionsByQuitReason = new ArrayList();

        while (sortedSessionsByNick.size() != 0) {

            String  entry = (String) sortedSessionsByNick.get(1);
            Pattern p     = Pattern.compile("^[^ ]+ [^ ]+ [^ ]+ (.*)$");
            Matcher m     = p.matcher(entry);

            if (m.matches()) {

                String currentQuitReason = m.group(1);

                sortedSessionsByQuitReason.add(sortedSessionsByNick.get(0));
                sortedSessionsByQuitReason.add(entry);
                sortedSessionsByNick.remove(0);
                sortedSessionsByNick.remove(0);
                for (int i = 0; i+1 < sortedSessionsByNick.size(); i += 2) {

                    String  nextEntry = (String) sortedSessionsByNick.get(i+1);
                    Pattern p2        = Pattern.compile("^[^ ]+ [^ ]+ [^ ]+ (.*)$");
                    Matcher m2        = p2.matcher(nextEntry);

                    if (m2.matches()) {

                        String nextQuitReason = m2.group(1);
 
                        if (nextQuitReason.equals(currentQuitReason)) {
                            sortedSessionsByQuitReason.add(sortedSessionsByNick.get(i));
                            sortedSessionsByQuitReason.add(nextEntry);
                            sortedSessionsByNick.remove(i);
                            sortedSessionsByNick.remove(i);
                            i -= 2;
                        }
                    } else {
                        System.out.println("\r\nError: Unexpected entry in logfile: " + nextEntry);
                        System.exit(1);
                    }
                }
            } else {
                System.out.println("\r\nError: Unexpected entry in logfile: " + entry);
                System.exit(1);
            }
        }
        return sortedSessionsByQuitReason;
    }

    /**
     * Sessions terminated with "parts" messages instead of "quits" are filtered
     * out.
     */
    private ArrayList sortSessionsByTime(ArrayList log) {

        ArrayList sortedSessionsByTime = new ArrayList();

        mainLoop:
            while (log.size() > 0) {
                
                String   entry       = (String) log.get(0);
                String[] entryFields = entry.split(" ");
                
                if (entryFields[1].equals("quits") && !entryFields[1].equals("joins")) {
                    /*
                     * Discard entry. The specified log either doesn't contain
                     * the corresponding "joins" time for this quit entry or the
                     * entry is a "parts" or unknown message, and in both cases
                     * the entry's data is useless.
                     */
                    log.remove(0);
                    continue;
                }
                
                for (int i = 1; i < log.size(); i++) {  // Find corresponding "quits" entry.
                    
                    String   tempEntry       = (String) log.get(i);
                    String[] tempEntryFields = tempEntry.split(" ");
                    
                    if (tempEntryFields[2].equals(entryFields[2])) {  // Check if the nick fields for the two entries match.
                        if (!tempEntryFields[1].equals("quits")) {
                            if (tempEntryFields[1].equals("joins")) {  // Don't discard a subsequent "joins" entry.
                                log.remove(0);
                                continue mainLoop;
                            }
                            log.remove(i);
                            continue;
                        }
                        sortedSessionsByTime.add(entry);
                        sortedSessionsByTime.add(tempEntry);
                        log.remove(i);
                        break;
                    }
                }
                /*
                 * Discard "joins" entry. The specified log doesn't contain the
                 * corresponding "quits" time for this entry so the entry's
                 * data is useless.
                 */
                
                log.remove(0);
            }

        return sortedSessionsByTime;
    }
}
