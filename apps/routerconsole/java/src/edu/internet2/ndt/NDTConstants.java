package edu.internet2.ndt;

import java.util.Locale;
import java.util.ResourceBundle;

import javax.swing.JOptionPane;

/**
 * 
 * Class to hold constants. These constants include both Protocol related
 * constants and non-protocol related ones which are used by the software. The
 * different sections of constants are listed under appropriate "sections".
 * 
 */
public class NDTConstants {

	// Section: System variables
	// used by the META tests
	public static final String META_CLIENT_OS = "client.os.name";
	public static final String META_BROWSER_OS = "client.browser.name";
	public static final String META_CLIENT_KERNEL_VERSION = "client.kernel.version";
	public static final String META_CLIENT_VERSION = "client.version";
        public static final String META_CLIENT_APPLICATION = "client.application";

        // Section: NDT Variables sent by server
        public static final String AVGRTT = "avgrtt";
        public static final String CURRWINRCVD = "CurRwinRcvd";
        public static final String MAXRWINRCVD = "MaxRwinRcvd";
        public static final String LOSS = "loss";
        public static final String MINRTT = "MinRTT";
        public static final String MAXRTT = "MaxRTT";
        public static final String WAITSEC = "waitsec";
        public static final String CURRTO = "CurRTO";
        public static final String SACKSRCVD = "SACKsRcvd";
        public static final String MISMATCH = "mismatch";
        public static final String BAD_CABLE = "bad_cable";
        public static final String CONGESTION = "congestion";
        public static final String CWNDTIME = "cwndtime";
        public static final String RWINTIME = "rwintime";
        public static final String OPTRCVRBUFF = "optimalRcvrBuffer";
        public static final String ACCESS_TECH = "accessTech";
        public static final String DUPACKSIN = "DupAcksIn";

	/*
	 * TODO for a later release: Version could be moved to some "configurable"
	 * or "property" area instead of being in code that needs compilation.
	 */
	public static final String VERSION = "3.7.0";

	public static final String NDT_TITLE_STR = "Network Diagnostic Tool Client v";

	// Section: Test type
	public static final byte TEST_MID = (1 << 0);
	public static final byte TEST_C2S = (1 << 1);
	public static final byte TEST_S2C = (1 << 2);
	public static final byte TEST_SFW = (1 << 3);
	public static final byte TEST_STATUS = (1 << 4);
	public static final byte TEST_META = (1 << 5);

	// Section: Firewall test status
	public static final int SFW_NOTTESTED = 0;
	public static final int SFW_NOFIREWALL = 1;
	public static final int SFW_UNKNOWN = 2;
	public static final int SFW_POSSIBLE = 3;

	public static final double VIEW_DIFF = 0.1;

	public static final String TARGET1 = "U";
	public static final String TARGET2 = "H";

	// NDT pre-fixed port ID
	public static final int CONTROL_PORT_DEFAULT = 3001;

	// Section: SRV-QUEUE message status constants
	public static final int SRV_QUEUE_TEST_STARTS_NOW = 0;
	public static final int SRV_QUEUE_SERVER_FAULT = 9977;
	public static final int SRV_QUEUE_SERVER_BUSY = 9988;
	public static final int SRV_QUEUE_HEARTBEAT = 9990;
	public static final int SRV_QUEUE_SERVER_BUSY_60s = 9999;

	// Section: Middlebox test related constants
	public static final int MIDDLEBOX_PREDEFINED_MSS = 8192;// 8k buffer size
	public static final int ETHERNET_MTU_SIZE = 1456;

	// Section: SFW test related constants
	public static final String SFW_PREDEFINED_TEST_MESSAGE = "Simple firewall test";

	private static ResourceBundle _rscBundleMessages;
	public static final String TCPBW100_MSGS = "edu.internet2.ndt.locale.Tcpbw100_msgs";
	public static final int PREDEFINED_BUFFER_SIZE = 8192; // 8k buffer size

	// Section: Data rate indicator integers
	public static final int DATA_RATE_INSUFFICIENT_DATA = -2;
	public static final int DATA_RATE_SYSTEM_FAULT = -1;
	public static final int DATA_RATE_RTT = 0;
	public static final int DATA_RATE_DIAL_UP = 1;
	public static final int DATA_RATE_T1 = 2;
	public static final int DATA_RATE_ETHERNET = 3;
	public static final int DATA_RATE_T3 = 4;
	public static final int DATA_RATE_FAST_ETHERNET = 5;
	public static final int DATA_RATE_OC_12 = 6;
	public static final int DATA_RATE_GIGABIT_ETHERNET = 7;
	public static final int DATA_RATE_OC_48 = 8;
	public static final int DATA_RATE_10G_ETHERNET = 9;
	// public static final int DATA_RATE_RETRANSMISSIONS = 10;

	// Section: Data rate indicator strings
	public static final String T1_STR = "T1";
	public static final String T3_STR = "T3";
	public static final String ETHERNET_STR = "Ethernet";
	public static final String FAST_ETHERNET = "FastE";
	public static final String OC_12_STR = "OC-12";
	public static final String GIGABIT_ETHERNET_STR = "GigE";
	public static final String OC_48_STR = "OC-48";
	public static final String TENGIGABIT_ETHERNET_STR = "10 Gig";
	public static final String SYSTEM_FAULT_STR = "systemFault";
	public static final String DIALUP_STR = "dialup2"; // unused, commenting out
														// for now
	public static final String RTT_STR = "rtt"; // round trip time

	// Section: RFC 1323 options ( Seems like 0/1/2/3 are the options available)
	
	public static final int RFC_1323_DISABLED = 0;
	public static final int RFC_1323_ENABLED = 1;
 	// Note Self disabled from servers standpoint i.e. disabled by server
 	public static final int RFC_1323_SELF_DISABLED = 2;
 	public static final int RFC_1323_PEER_DISABLED = 3;
 	
 	// Section: RFC2018 SAck
 	public static final int RFC_2018_ENABLED = 1;
 	
 	// Section: RFC2018 Nagle
 	public static final int RFC_896_ENABLED = 1;
 	
 	// Section: RFC3168
 	public static final int RFC_3168_ENABLED = 1;
 	// Note Self disabled from servers standpoint i.e. disabled by server
 	public static final int RFC_3168_SELF_DISABLED = 2;
 	public static final int RFC_3168_PEER_DISABLED = 3;
 	
	// Section: Buffer limitation test thresholds
	public static final float BUFFER_LIMITED = 0.15f; //unused right now
	
	
	// Section: TCP constants
	public static final int TCP_MAX_RECV_WIN_SIZE = 65535;
	 
	// Section: Data units
	public static final int KILO = 1000; // Used in conversions from seconds->mS,
	public static final int KILO_BITS = 1024;// Used in kilobits->bits conversions
	public static final double EIGHT = 8.0; // Used in octal number, conversions from Bytes-> bits etc
        // EIGHT is a double to minimize overflow when converting.

	// Section: Duplex mismatch conditions
	public static final int DUPLEX_OK_INDICATOR = 0;
	public static final int DUPLEX_NOK_INDICATOR = 1;
	public static final int DUPLEX_SWITCH_FULL_HOST_HALF = 2;
	public static final int DUPLEX_SWITCH_HALF_HOST_FULL = 3;
	public static final int DUPLEX_SWITCH_FULL_HOST_HALF_POSS = 4;
	public static final int DUPLEX_SWITCH_HALF_HOST_FULL_POSS = 5;
	public static final int DUPLEX_SWITCH_HALF_HOST_FULL_WARN = 7;
	
	// Section: cable status indicators
	public static final int CABLE_STATUS_OK = 0;
	public static final int CABLE_STATUS_BAD = 1;
	
	// Section: Congestion status
	public static final int CONGESTION_NONE = 0;
	public static final int CONGESTION_FOUND = 1;
	
	// Section: miscellaneous
	public static final int SOCKET_FREE_PORT_INDICATOR = 0;
	public static final String LOOPBACK_ADDRS_STRING = "127.0.0.1";
	public static final int PERCENTAGE = 100;

	// constant to indicate protocol read success
	public static final int PROTOCOL_MSG_READ_SUCCESS = 0;

	// system variables could be declared as strings too
	// half_duplex:, country , etc.

	/**
	 * Initializes a few constants
	 * 
	 * @param paramLocale
	 *            local Locale object
	 * */
	public static void initConstants(Locale paramLocale) {
		try {
			_rscBundleMessages = ResourceBundle.getBundle(TCPBW100_MSGS,
					paramLocale);
			System.out.println("Obtained messages ");
		} catch (Exception e) {
			JOptionPane.showMessageDialog(null,
					"Error while loading language files:\n" + e.getMessage());
			e.printStackTrace();
		}

	} // end method

	/**
	 * Initializes a few constants
	 * 
	 * @param paramStrLang
	 *            local Language String
	 * @param paramStrCountry
	 *            local country String
	 * */
	public static void initConstants(String paramStrLang, String paramStrCountry) {
		try {
			Locale locale = new Locale(paramStrLang, paramStrCountry);
			_rscBundleMessages = ResourceBundle.getBundle(TCPBW100_MSGS,
					locale);
		} catch (Exception e) {
			JOptionPane.showMessageDialog(null,
					"Error while loading language files:\n" + e.getMessage());
			e.printStackTrace();
		}
	}// end method initconstants

	/**
	 * Getter method for to fetch from resourceBundle
	 * 
	 * @param paramStrName
	 *            name of parameter to be fetched
	 * @return Value of parameter input
	 */
	public static String getMessageString(String paramStrName) {
		return _rscBundleMessages.getString(paramStrName);
	}

}
