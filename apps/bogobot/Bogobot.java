/*
 * bogobot - A simple join/part stats logger bot for I2P IRC.
 * 
 * Bogobot.java
 * 2004 The I2P Project
 * This code is public domain.
 */

import java.io.File;
import java.io.IOException;

import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.NickAlreadyInUseException;
import org.jibble.pircbot.PircBot;
import org.jibble.pircbot.User;

/**
 * TODO 0.4 Implement java.util.Properties for configuration.
 * TODO 0.4 Add NickServ interface.
 * TODO 0.5 Add multi-server capability.
 * 
 * @author hypercubus
 * @version 0.3.1
 */
public class Bogobot extends PircBot {

    private static final String INTERVAL_DAILY   = "daily";
    private static final String INTERVAL_MONTHLY = "monthly";
    private static final String INTERVAL_WEEKLY  = "weekly";

    private boolean _isIntentionalDisconnect      = false;
    private long    _lastUserlistCommandTimestamp = 0;
    private Logger  _logger                       = Logger.getLogger(Bogobot.class);
    /*
     * The following will soon be moved to bogo.config and loaded via
     * java.util.Properties when I get some time.
     */
    private String  _botPrimaryNick           = "somebot";
    private String  _botSecondaryNick         = "somebot_";
    private String  _botShutdownPassword      = "take off eh";
    private long    _commandAntiFloodInterval = 60;
    private String  _ircChannel               = "#i2p-chat";
    private String  _ircServer                = "irc.duck.i2p";
    private int     _ircServerPort            = 6668;
    private boolean _isLoggerEnabled          = true;
    private boolean _isUserlistCommandEnabled = true;
    private String  _logFilePrefix            = "irc.duck.i2p.i2p-chat";
    private String  _logFileRotationInterval  = INTERVAL_DAILY;
    private String  _ownerPrimaryNick         = "somenick";
    private String  _ownerSecondaryNick       = "somenick_";
    private String  _userlistCommandTrigger   = "!who";

    public Bogobot() {
        this.setName(_botPrimaryNick);
    }

    public static void main(String[] args) {

        Bogobot bogobot = new Bogobot();

        bogobot.setVerbose(true);

        if (bogobot._isLoggerEnabled)
            bogobot.initLogger();

        bogobot.connectToServer();
    }

    protected void onDisconnect() {

        if (_isIntentionalDisconnect)
            System.exit(0);            

        if (_isLoggerEnabled)
            _logger.info(System.currentTimeMillis() + " quits *** " + this.getName() + " *** (Lost connection)");

        try {
            Thread.sleep(60000);
        } catch (InterruptedException e) {
            // No worries.
        }
        connectToServer();
    }

    protected void onJoin(String channel, String sender, String login, String hostname) {
        if (sender.equals(this.getName())) {

            if (_isLoggerEnabled)
                _logger.info(System.currentTimeMillis() + " joins *** " + _botPrimaryNick + " ***");

        } else {

            if (_isLoggerEnabled)
                _logger.info(System.currentTimeMillis() + " joins " + sender);

        }
    }

    protected void onMessage(String channel, String sender, String login, String hostname, String message) {
        message = message.replaceFirst("<.+?> ", "");
        if (_isUserlistCommandEnabled && message.equals(_userlistCommandTrigger)) {

            if (System.currentTimeMillis() - _lastUserlistCommandTimestamp < _commandAntiFloodInterval * 1000)
                return;

            Object[] users = getUsers(_ircChannel);
            String output = "Userlist for " + _ircChannel + ": ";

            for (int i = 0; i < users.length; i++)
                output += "[" + ((User) users[i]).getNick() + "] ";

            sendMessage(_ircChannel, output);
            _lastUserlistCommandTimestamp = System.currentTimeMillis();
        }
    }

    protected void onPart(String channel, String sender, String login, String hostname) {

        if (_isLoggerEnabled)
            _logger.info(System.currentTimeMillis() + " parts " + sender);

    }

    protected void onPrivateMessage(String sender, String login, String hostname, String message) {
        /*
         * Nobody else except the bot's owner can shut it down, unless of
         * course the owner's nick isn't registered and someone's spoofing it.
         */
        if ((sender.equals(_ownerPrimaryNick) || sender.equals(_ownerSecondaryNick)) && message.equals(_botShutdownPassword)) {

            if (_isLoggerEnabled)
                _logger.info(System.currentTimeMillis() + " quits *** " + this.getName() + " ***");

            _isIntentionalDisconnect = true;
            disconnect();
        }
    }

    protected void onQuit(String sourceNick, String sourceLogin, String sourceHostname, String reason) {

        if (_isLoggerEnabled)
            _logger.info(System.currentTimeMillis() + " quits " + sourceNick + " " + reason);

    }

    private void connectToServer() {

        int loginAttempts = 0;

        while (true) {
            try {
                connect(_ircServer, _ircServerPort);
                break;
            } catch (NickAlreadyInUseException e) {
                if (loginAttempts == 1) {
                    System.out.println("Sorry, the primary and secondary bot nicks are already taken. Exiting.");
                    System.exit(1);
                }
                loginAttempts++;
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e1) {
                    // Hmph.
                }

                if (getName().equals(_botPrimaryNick))
                    setName(_botSecondaryNick);
                else
                    setName(_botPrimaryNick);

                continue;
            } catch (IOException e) {
                System.out.println("Error during login: ");
                e.printStackTrace();
                System.exit(1);
            } catch (IrcException e) {
                System.out.println("Error during login: ");
                e.printStackTrace();
                System.exit(1);
            }
        }
        joinChannel(_ircChannel);
    }

    private void initLogger() {

        String                   logFilePath         = "logs" + File.separator + _logFilePrefix;
        DailyRollingFileAppender rollingFileAppender = null;

        if (!(new File("logs").exists()))
            (new File("logs")).mkdirs();

        try {

            if (_logFileRotationInterval.equals("monthly"))
                rollingFileAppender = new DailyRollingFileAppender(new PatternLayout("%m%n"), logFilePath, "'.'yyyy-MM'.log'");
            else if (_logFileRotationInterval.equals("weekly"))
                rollingFileAppender = new DailyRollingFileAppender(new PatternLayout("%m%n"), logFilePath, "'.'yyyy-ww'.log'");
            else
                rollingFileAppender = new DailyRollingFileAppender(new PatternLayout("%m%n"), logFilePath, "'.'yyyy-MM-dd'.log'");

            rollingFileAppender.setThreshold(Level.INFO);
            _logger.addAppender(rollingFileAppender);
        } catch (IOException ex) {
            System.out.println("Error: Couldn't create or open an existing log file. Exiting.");
            System.exit(1);
        }
    }
}
