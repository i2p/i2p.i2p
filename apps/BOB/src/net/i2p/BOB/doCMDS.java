/**
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 * Everyone is permitted to copy and distribute verbatim or modified
 * copies of this license document, and changing it is allowed as long
 * as the name is changed.
 *
 *            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
 *   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
 *
 *  0. You just DO WHAT THE FUCK YOU WANT TO.
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and liscense questions.
 */
package net.i2p.BOB;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Properties;
import java.util.StringTokenizer;
import net.i2p.I2PException;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.util.Log;

/**
 * Simplistic command parser for BOB
 *
 * @author sponge
 *
 */
public class doCMDS implements Runnable {

	// FIX ME
	// I need a better way to do versioning, but this will do for now.
	public static final String BMAJ = "00",  BMIN = "00",  BREV = "01",  BEXT = "-8";
	public static final String BOBversion = BMAJ + "." + BMIN + "." + BREV + BEXT;
	private Socket server;
	private Properties props;
	private nickname database;
	private String line;
	private Destination d;
	private ByteArrayOutputStream prikey;
	private boolean dk,  ns,  ip,  op;
	private nickname nickinfo;
	private Log _log;
	/* database strings */
	private static final String P_DEST = "DESTINATION";
	private static final String P_INHOST = "INHOST";
	private static final String P_INPORT = "INPORT";
	private static final String P_KEYS = "KEYS";
	private static final String P_NICKNAME = "NICKNAME";
	private static final String P_OUTHOST = "OUTHOST";
	private static final String P_OUTPORT = "OUTPORT";
	private static final String P_PROPERTIES = "PROPERTIES";
	private static final String P_QUIET = "QUIET";
	private static final String P_RUNNING = "RUNNING";
	private static final String P_STARTING = "STARTING";
	private static final String P_STOPPING = "STOPPING";
	/* command strings */
	private static final String C_help = "help";
	private static final String C_clear = "clear";
	private static final String C_getdest = "getdest";
	private static final String C_getkeys = "getkeys";
	private static final String C_getnick = "getnick";
	private static final String C_inhost = "inhost";
	private static final String C_inport = "inport";
	private static final String C_list = "list";
	private static final String C_newkeys = "newkeys";
	private static final String C_option = "option";
	private static final String C_outhost = "outhost";
	private static final String C_outport = "outport";
	private static final String C_quiet = "quiet";
	private static final String C_quit = "quit";
	private static final String C_setkeys = "setkeys";
	private static final String C_setnick = "setnick";
	private static final String C_show = "show";
	private static final String C_start = "start";
	private static final String C_status = "status";
	private static final String C_stop = "stop";

	/* all the coomands available, plus description */
	private static final String C_ALL[][] = {
		{C_help, C_help + " <command> * Get help on a command."},
		{C_clear, C_clear + " * Clear the current nickname out of the list."},
		{C_getdest, C_getdest + " * Return the destination for the current nickname."},
		{C_getkeys, C_getkeys + " * Return the keypair for the current nickname."},
		{C_getnick, C_getnick + " tunnelname * Set the nickname from the database."},
		{C_inhost, C_inhost + " hostname | IP * Set the inbound hostname or IP."},
		{C_inport, C_inport + " port_number * Set the inbound port number nickname listens on."},
		{C_list, C_list + " * List all tunnels."},
		{C_newkeys, C_newkeys + " * Generate a new keypair for the current nickname."},
		{C_option, C_option + " I2CPoption=something * Set an I2CP option. NOTE: Don't use any spaces."},
		{C_outhost, C_outhost + " hostname | IP * Set the outbound hostname or IP."},
		{C_outport, C_outport + " port_number * Set the outbound port that nickname contacts."},
		{C_quiet, C_quiet + " True | False * Don't send to the application the incoming destination."},
		{C_quit, C_quit + " * Quits this session with BOB."},
		{C_setkeys, C_setkeys + " BASE64_keypair * Sets the keypair for the current nickname."},
		{C_setnick, C_setnick + " nickname * Create a new nickname."},
		{C_show, C_show + " * Display the status of the current nickname."},
		{C_start, C_start + " * Start the current nickname tunnel."},
		{C_status, C_status + " nickname * Display status of a nicknamed tunnel."},
		{C_stop, C_stop + " * Stops the current nicknamed tunnel."},
		{"", "COMMANDS: " + // this is ugly, but...
			C_help + " " +
			C_clear + " " +
			C_getdest + " " +
			C_getkeys + " " +
			C_getnick + " " +
			C_inhost + " " +
			C_inport + " " +
			C_list + " " +
			C_newkeys + " " +
			C_option + " " +
			C_outhost + " " +
			C_outport + " " +
			C_quiet + " " +
			C_quit + " " +
			C_setkeys + " " +
			C_setnick + " " +
			C_show + " " +
			C_start + " " +
			C_status + " " +
			C_stop
		},
		{" ", " "} // end of list
	};

	/**
	 *
	 * @param server
	 * @param props
	 * @param database
	 * @param _log
	 */
	doCMDS(Socket server, Properties props, nickname database, Log _log) {
		this.server = server;
		this.props = props;
		this.database = database;
		this._log = _log;
	}

	/**
	 * Try to print info from the database
	 *
	 * @param out
	 * @param info
	 * @param key
	 */
	public void trypnt(PrintStream out, nickname info, Object key) {
		out.print(" " + key + ": ");
		if(info.exists(key)) {
			out.print(info.get(key));
		} else {
			out.print("not_set");
		}
	}

	/**
	 * Print true or false if an object exists
	 *
	 * @param out
	 * @param info
	 * @param key
	 */
	public void tfpnt(PrintStream out, nickname info, Object key) {
		out.print(" " + key + ": ");
		out.print(info.exists(key));
	}

	/**
	 * Print an error message
	 *
	 * @param out
	 */
	public void nns(PrintStream out) {
		out.println("ERROR no nickname has been set");
	}

	/**
	 * Dump various information from the database
	 *
	 * @param out
	 * @param info
	 */
	public void nickprint(PrintStream out, nickname info) {
		trypnt(out, info, P_NICKNAME);
		trypnt(out, info, P_STARTING);
		trypnt(out, info, P_RUNNING);
		trypnt(out, info, P_STOPPING);
		tfpnt(out, info, P_KEYS);
		trypnt(out, info, P_QUIET);
		trypnt(out, info, P_INPORT);
		trypnt(out, info, P_INHOST);
		trypnt(out, info, P_OUTPORT);
		trypnt(out, info, P_OUTHOST);
		out.println();

	}

	/**
	 * Print information on a specific record, indicated by nickname
	 * @param out
	 * @param database
	 * @param Arg
	 */
	public void ttlpnt(PrintStream out, nickname database, Object Arg) {
		if(database.exists(Arg)) {
			out.print("DATA");
			nickprint(out, (nickname)database.get(Arg));
		}
	}

	/**
	 * Is this nickname's tunnel active?
	 *
	 * @param Arg
	 * @return true if the tunnel is active
	 */
	public boolean tunnelactive(nickname Arg) {
		return (Arg.get(P_STARTING).equals(Boolean.TRUE) ||
			Arg.get(P_STOPPING).equals(Boolean.TRUE) ||
			Arg.get(P_RUNNING).equals(Boolean.TRUE));

	}

	/**
	 * Does the base64 information look OK
	 *
	 * @param data
	 * @return
	 */
	private boolean is64ok(String data) {
		String dest = new String(data);
		if(dest.replaceAll("[a-zA-Z0-9~-]", "").length() == 0) {
			return true;
		}
		return false;
	}

	/**
	 * The actual parser.
	 * It probabbly needs a rewrite into functions, but I kind-of like inline code.
	 *
	 */
	public void run() {
		dk = ns = ip = op = false;

		try {
			// Get input from the client
			BufferedReader in = new BufferedReader(new InputStreamReader(server.getInputStream()));
			PrintStream out = new PrintStream(server.getOutputStream());
			prikey = new ByteArrayOutputStream();
			out.println("BOB " + BOBversion);
			out.println("OK");
			while((line = in.readLine()) != null) {
				System.gc(); // yes, this does make a huge difference...
				StringTokenizer token = new StringTokenizer(line, " "); // use a space as a delimiter
				String Command = "";
				String Arg = "";
				nickname info;

				if(token.countTokens() != 0) {
					Command = token.nextToken();
					Command = Command.toLowerCase();
					if(token.countTokens() != 0) {
						Arg = token.nextToken();
					} else {
						Arg = "";
					}
					// The rest of the tokens are considered junk,
					// and discarded without any warnings.

					if(Command.equals(C_help)) {
						for(int i = 0; !C_ALL[i][0].equals(" "); i++) {
							if(C_ALL[i][0].equalsIgnoreCase(Arg)) {
								out.println("OK " + C_ALL[i][1]);
							}
						}
					} else if(Command.equals(C_getdest)) {
						if(ns) {
							if(dk) {
								out.println("OK " + nickinfo.get(P_DEST));
							} else {
								out.println("ERROR keys not set.");
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_list)) {
						// Produce a formatted list of all nicknames
						for(int i = 0; i < database.getcount(); i++) {
							try {
								info = (nickname)database.getnext(i);
							} catch(RuntimeException b) {
								break; // something bad happened.
							}

							out.print("DATA");
							nickprint(out, info);
						}
						out.println("OK Listing done");
					} else if(Command.equals(C_quit)) {
						// End the command session
						break;
					} else if(Command.equals(C_newkeys)) {
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								try {
									// Make a new PublicKey and PrivateKey
									prikey = new ByteArrayOutputStream();
									d = I2PClientFactory.createClient().createDestination(prikey);
									dk = true;
									nickinfo.add(P_KEYS, prikey.toByteArray());
									nickinfo.add(P_DEST, d.toBase64());
									// System.out.println(prikey.toByteArray().length);
									out.println("OK " + nickinfo.get(P_DEST));
								} catch(IOException ioe) {
									BOB.error("Error generating keys" + ioe);
									out.println("ERROR generating keys");
								} catch(I2PException ipe) {
									BOB.error("Error generating keys" + ipe);
									out.println("ERROR generating keys");
								}
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_getkeys)) {
						// Return public key
						if(dk) {
							prikey = new ByteArrayOutputStream();
							prikey.write(((byte[])nickinfo.get(P_KEYS)));
							out.println("OK " + net.i2p.data.Base64.encode(prikey.toByteArray()));
						} else {
							out.println("ERROR no public key has been set");
						}
					} else if(Command.equals(C_quiet)) {
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								nickinfo.add(P_QUIET, new Boolean(Boolean.parseBoolean(Arg) == true));
								out.println("OK Quiet set");
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_setkeys)) {
						// Set the nickname to a privatekey in BASE64 format
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								try {
									prikey = new ByteArrayOutputStream();
									prikey.write(net.i2p.data.Base64.decode(Arg));
									d.fromBase64(Arg);
								} catch(Exception ex) {
									Arg = "";
								}
								if((Arg.length() == 884) && is64ok(Arg)) {
									nickinfo.add(P_KEYS, prikey.toByteArray());
									nickinfo.add(P_DEST, d.toBase64());
									out.println("OK " + nickinfo.get(P_DEST));
									dk = true;
								} else {
									out.println("ERROR not in BASE64 format");
								}
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_setnick)) {
						ns = dk = ip = op = false;
						try {
							nickinfo = (nickname)database.get(Arg);
							if(!tunnelactive(nickinfo)) {
								nickinfo = null;
								ns = true;
							}
						} catch(RuntimeException b) {
							nickinfo = null;
							ns = true;
						}

						// Clears and Sets the initial nickname structure to work with
						if(ns) {
							nickinfo = new nickname();
							database.add(Arg, nickinfo);
							nickinfo.add(P_NICKNAME, Arg);
							nickinfo.add(P_STARTING, Boolean.FALSE);
							nickinfo.add(P_RUNNING, Boolean.FALSE);
							nickinfo.add(P_STOPPING, Boolean.FALSE);
							nickinfo.add(P_QUIET, Boolean.FALSE);
							nickinfo.add(P_INHOST, "localhost");
							nickinfo.add(P_OUTHOST, "localhost");
							Properties Q = props;
							Q.setProperty("inbound.nickname", (String)nickinfo.get(P_NICKNAME));
							Q.setProperty("outbound.nickname", (String)nickinfo.get(P_NICKNAME));
							nickinfo.add(P_PROPERTIES, Q);
							out.println("OK Nickname set to " + Arg);
						} else {
							out.println("ERROR tunnel is active");
						}
					} else if(Command.equals(C_option)) {
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								StringTokenizer otoken = new StringTokenizer(Arg, "="); // use a space as a delimiter
								if(otoken.countTokens() != 2) {
									out.println("ERROR to many or no options.");
								} else {
									String pname = otoken.nextToken();
									String pval = otoken.nextToken();
									Properties Q = (Properties)nickinfo.get(P_PROPERTIES);
									Q.setProperty(pname, pval);
									nickinfo.add(P_PROPERTIES, Q);
									out.println("OK " + pname + " set to " + pval);
								}
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_getnick)) {
						// Get the nickname to work with...
						try {
							nickinfo = (nickname)database.get(Arg);
							ns = true;
						} catch(RuntimeException b) {
							nns(out);
						}
						if(ns) {
							dk = nickinfo.exists(P_KEYS);
							ip = nickinfo.exists(P_INPORT);
							op = nickinfo.exists(P_OUTPORT);
							// Finally say OK.
							out.println("OK Nickname set to " + Arg);
						}
					} else if(Command.equals(C_inport)) {
						// Set the nickname inbound TO the router port
						// app --> BOB
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								int prt;
								nickinfo.kill(P_INPORT);
								try {
									prt = Integer.parseInt(Arg);
									if(prt > 1 && prt < 65536) {
										nickinfo.add(P_INPORT, new Integer(prt));
									}
								} catch(NumberFormatException nfe) {
									out.println("ERROR not a number");
								}
								ip = nickinfo.exists(P_INPORT);
								if(ip) {
									out.println("OK inbound port set");
								} else {
									out.println("ERROR port out of range");
								}
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_outport)) {
						// Set the nickname outbound FROM the router port
						// BOB --> app
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								int prt;
								nickinfo.kill(P_OUTPORT);
								try {
									prt = Integer.parseInt(Arg);
									if(prt > 1 && prt < 65536) {
										nickinfo.add(P_OUTPORT, new Integer(prt));
									}
								} catch(NumberFormatException nfe) {
									out.println("ERROR not a number");
								}
								ip = nickinfo.exists(P_OUTPORT);
								if(ip) {
									out.println("OK outbound port set");
								} else {
									out.println("ERROR port out of range");
								}
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_inhost)) {
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								nickinfo.add(P_INHOST, Arg);
								out.println("OK inhost set");
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_outhost)) {
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								nickinfo.add(P_OUTHOST, Arg);
								out.println("OK outhost set");
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_show)) {
						// Get the current nickname properties
						if(ns) {
							out.print("OK");
							nickprint(out, nickinfo);
						} else {
							nns(out);
						}
					} else if(Command.equals(C_start)) {
						// Start the tunnel, if we have all the information
						if(ns && dk && (ip || op)) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								MUXlisten tunnel;
								try {
									tunnel = new MUXlisten(nickinfo, _log);
									Thread t = new Thread(tunnel);
									nickinfo.add(P_STARTING, Boolean.TRUE);
									t.start();
									out.println("OK tunnel starting");
								} catch(I2PException e) {
									out.println("ERROR starting tunnel: " + e);
								} catch(IOException e) {
									out.println("ERROR starting tunnel: " + e);
								}
							}
						} else {
							out.println("ERROR tunnel settings incomplete");
						}
					} else if(Command.equals(C_stop)) {
						// Stop the tunnel, if it is running
						if(ns) {
							if(nickinfo.get(P_RUNNING).equals(Boolean.TRUE) && nickinfo.get(P_STOPPING).equals(Boolean.FALSE)) {
								nickinfo.add(P_STOPPING, Boolean.TRUE);
								out.println("OK tunnel stopping");
							} else {
								out.println("ERROR tunnel is inactive");
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_clear)) {
						// Clear use of the nickname if stopped
						if(ns) {
							if(tunnelactive(nickinfo)) {
								out.println("ERROR tunnel is active");
							} else {
								database.kill(nickinfo.get(P_NICKNAME));
								dk = ns = ip = op = false;
								out.println("OK cleared");
							}
						} else {
							nns(out);
						}
					} else if(Command.equals(C_status)) {
						if(database.exists(Arg)) {
							// Show status of a nickname
							out.print("OK ");
							ttlpnt(out, database, Arg);
						} else {
							nns(out);
						}
					} else {
						out.println("ERROR UNKNOWN COMMAND! Try help");
					}
				}
			}

			// Say goodbye.

			out.println("OK Bye!");

			server.close();
		} catch(IOException ioe) {
			BOB.warn("IOException on socket listen: " + ioe);
			ioe.printStackTrace();
		}
	}
}
