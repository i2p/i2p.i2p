/*
 * bogobot - A simple join/part stats logger bot for I2P IRC.
 * 
 * Bogobot.java
 * 2004 The I2P Project
 * This code is public domain.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import java.util.Timer;
import java.util.TimerTask;
import java.util.Properties;

import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

import org.jibble.pircbot.IrcException;
import org.jibble.pircbot.NickAlreadyInUseException;
import org.jibble.pircbot.PircBot;
import org.jibble.pircbot.User;

/**
 * TODO 0.5 Add multi-server capability.
 * 
 * @author hypercubus, oOo
 * @version 0.4
 */
public class Bogobot extends PircBot {

    private static final String INTERVAL_DAILY   = "daily";
    private static final String INTERVAL_MONTHLY = "monthly";
    private static final String INTERVAL_WEEKLY  = "weekly";

    private boolean _isIntentionalDisconnect      = false;
    private long    _lastUserlistCommandTimestamp = 0;
    private Logger  _logger                       = Logger.getLogger(Bogobot.class);

    private int     _currentAutoRoundTripTag      = 0;
    private long    _lastAutoRoundTripSentTime    = 0;
    private Timer   _tickTimer;

    private String  _configFile;

    private String  _botPrimaryNick;
    private String  _botSecondaryNick;
    private String  _botNickservPassword;
    private String  _botUsername;
    private String  _ownerPrimaryNick;
    private String  _ownerSecondaryNick;
    private String  _botShutdownPassword;
    private String  _ircChannel;
    private String  _ircServer;
    private int     _ircServerPort;
    private boolean _isLoggerEnabled;
    private String  _loggedHostnamePattern;
    private boolean _isUserlistCommandEnabled;
    private String  _logFilePrefix;
    private String  _logFileRotationInterval;
    private long    _commandAntiFloodInterval;
    private String  _userlistCommandTrigger;
    private boolean _isRoundTripDelayEnabled;
    private int     _roundTripDelayPeriod;

    class BogobotTickTask extends TimerTask {
        private Bogobot _caller;

        public BogobotTickTask(Bogobot caller) {
            _caller = caller;
        }

        public void run() {
          _caller.onTick();
        }
    }

    private void loadConfigFile(String configFileName) {

        _configFile = configFileName;

        Properties config = new Properties();
        FileInputStream fis = null;

        try {
            fis = new FileInputStream(configFileName);
            config.load(fis);
        } catch (IOException ioe) {
            System.err.println("Error loading configuration file");
            System.exit(2);

        } finally {
            if (fis != null) try {
                fis.close();
            } catch (IOException ioe) { // nop
            }
        }

        _botPrimaryNick = config.getProperty("botPrimaryNick", "somebot");
        _botSecondaryNick = config.getProperty("botSecondaryNick", "somebot_");
        _botNickservPassword = config.getProperty("botNickservPassword", "");
        _botUsername = config.getProperty("botUsername", "somebot");

        _ownerPrimaryNick = config.getProperty("ownerPrimaryNick", "somenick");
        _ownerSecondaryNick = config.getProperty("ownerSecondaryNick", "somenick_");

        _botShutdownPassword = config.getProperty("botShutdownPassword", "take off eh");

        _ircChannel = config.getProperty("ircChannel", "#i2p-chat");
        _ircServer = config.getProperty("ircServer", "irc.duck.i2p");
        _ircServerPort = Integer.parseInt(config.getProperty("ircServerPort", "6668"));

        _isLoggerEnabled = Boolean.valueOf(config.getProperty("isLoggerEnabled", "true")).booleanValue();
        _loggedHostnamePattern = config.getProperty("loggedHostnamePattern", "");
        _logFilePrefix = config.getProperty("logFilePrefix", "irc.duck.i2p.i2p-chat");
        _logFileRotationInterval = config.getProperty("logFileRotationInterval", INTERVAL_DAILY);

        _isRoundTripDelayEnabled = Boolean.valueOf(config.getProperty("isRoundTripDelayEnabled", "false")).booleanValue();
        _roundTripDelayPeriod = Integer.parseInt(config.getProperty("roundTripDelayPeriod", "300"));

        _isUserlistCommandEnabled = Boolean.valueOf(config.getProperty("isUserlistCommandEnabled", "true")).booleanValue();
        _userlistCommandTrigger = config.getProperty("userlistCommandTrigger", "!who");
        _commandAntiFloodInterval = Long.parseLong(config.getProperty("commandAntiFloodInterval", "60"));
    }

    public Bogobot(String configFileName) {

        loadConfigFile(configFileName);

        this.setName(_botPrimaryNick);
        this.setLogin(_botUsername);
        _tickTimer = new Timer();
        _tickTimer.scheduleAtFixedRate(new BogobotTickTask(this), 1000, 10 * 1000);
    }

    public static void main(String[] args) {

        Bogobot bogobot;

        if (args.length > 1) {
            System.err.println("Too many arguments, the only allowed parameter is configuration file name");
            System.exit(3);
        }
        if (args.length == 1) {
          bogobot = new Bogobot(args[0]);
        } else {
          bogobot = new Bogobot("bogobot.config");
        }

        bogobot.setVerbose(true);

        if (bogobot._isLoggerEnabled)
            bogobot.initLogger();

        bogobot.connectToServer();
    }

    protected void onTick() {
        // Tick about once every ten seconds

        if (this.isConnected() && _isRoundTripDelayEnabled) {
            if( ( (System.currentTimeMillis() - _lastAutoRoundTripSentTime) >= (_roundTripDelayPeriod * 1000) ) && (this.getOutgoingQueueSize() == 0) ) {
                // Connected, sending queue is empty and last RoundTrip is more then 5 minutes old -> Send a new one
                _currentAutoRoundTripTag ++;
                _lastAutoRoundTripSentTime = System.currentTimeMillis();
                sendNotice(this.getNick(),"ROUNDTRIP " + _currentAutoRoundTripTag);
            }
        }
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

        if (_isLoggerEnabled) {
            if (sender.equals(this.getName())) {

                _logger.info(System.currentTimeMillis() + " joins *** " + _botPrimaryNick + " ***");

            } else {

                String prependedHostname = "@" + hostname;
                if (prependedHostname.endsWith(_loggedHostnamePattern)) {
                    _logger.info(System.currentTimeMillis() + " joins " + sender);
                }

            }
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

        if (_isLoggerEnabled) {
            if (sender.equals(this.getName())) {
                _logger.info(System.currentTimeMillis() + " parts *** " + _botPrimaryNick + " ***");
            } else {
                String prependedHostname = "@" + hostname;
                if (prependedHostname.endsWith(_loggedHostnamePattern)) {
                    _logger.info(System.currentTimeMillis() + " parts " + sender);
                }
            }
        }

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
        String prependedHostname = "@" + sourceHostname;

        if (sourceNick.equals(_botPrimaryNick))
            changeNick(_botPrimaryNick);

        if (_isLoggerEnabled) {
            if (prependedHostname.endsWith(_loggedHostnamePattern)) {
                _logger.info(System.currentTimeMillis() + " quits " + sourceNick + " " + reason);
            }
        }

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

    protected void onNotice(String sourceNick, String sourceLogin, String sourceHostname, String target, String notice) {

        if (sourceNick.equals("NickServ") && (notice.indexOf("/msg NickServ IDENTIFY") >= 0) && (_botNickservPassword != "")) {
            sendRawLineViaQueue("NICKSERV IDENTIFY " + _botNickservPassword);
        }

        if (sourceNick.equals(getNick()) && notice.equals( "ROUNDTRIP " + _currentAutoRoundTripTag)) {
            int delay = (int)((System.currentTimeMillis() - _lastAutoRoundTripSentTime) / 100);
//            sendMessage(_ircChannel, "Round-trip delay = " + (delay / 10.0f) + " seconds");
            if (_isLoggerEnabled)
                _logger.info(System.currentTimeMillis() + " roundtrip " + delay);
        }
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
