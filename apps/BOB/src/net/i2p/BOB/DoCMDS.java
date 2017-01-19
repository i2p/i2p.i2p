/**
 *                    WTFPL
 *                    Version 2, December 2004
 *
 * Copyright (C) sponge
 *   Planet Earth
 *
 * See...
 *
 *	http://sam.zoy.org/wtfpl/
 *	and
 *	http://en.wikipedia.org/wiki/WTFPL
 *
 * ...for any additional details and license questions.
 */
package net.i2p.BOB;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Locale;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicBoolean;

import net.i2p.I2PAppContext;
import net.i2p.I2PException;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.util.I2PAppThread;

// needed only for debugging.
// import java.util.logging.Level;
// import java.util.logging.Logger;

/**
 * Simplistic command parser for BOB
 *
 * @author sponge
 *
 */
public class DoCMDS implements Runnable {

	// FIX ME
	// I need a better way to do versioning, but this will do for now.
	public static final String BMAJ = "00",  BMIN = "00",  BREV = "10",  BEXT = "";
	public static final String BOBversion = BMAJ + "." + BMIN + "." + BREV + BEXT;
	private final Socket server;
	private final Properties props;
	private final NamedDB database;
	private String line;
	private Destination d;
	private ByteArrayOutputStream prikey;
	private boolean dk,  ns,  ip,  op;
	private NamedDB nickinfo;
	private final Logger _log;
	private final AtomicBoolean LIVE;
	private final AtomicBoolean lock;
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
	private static final String C_lookup = "lookup";
	private static final String C_newkeys = "newkeys";
	private static final String C_option = "option";
	private static final String C_outhost = "outhost";
	private static final String C_outport = "outport";
	private static final String C_quiet = "quiet";
	private static final String C_quit = "quit";
	private static final String C_setkeys = "setkeys";
	private static final String C_setnick = "setnick";
	private static final String C_show = "show";
	private static final String C_show_props = "showprops";
	private static final String C_start = "start";
	private static final String C_status = "status";
	private static final String C_stop = "stop";
	private static final String C_verify = "verify";
	private static final String C_visit = "visit";
	private static final String C_zap = "zap";

	/* all the commands available, plus description */
	private static final String C_ALL[][] = {
		{C_help, C_help + " <command> * Get help on a command."},
		{C_clear, C_clear + " * Clear the current nickname out of the list."},
		{C_getdest, C_getdest + " * Return the destination for the current nickname."},
		{C_getkeys, C_getkeys + " * Return the keypair for the current nickname."},
		{C_getnick, C_getnick + " tunnelname * Set the nickname from the database."},
		{C_inhost, C_inhost + " hostname | IP * Set the inbound hostname or IP."},
		{C_inport, C_inport + " port_number * Set the inbound port number nickname listens on."},
		{C_list, C_list + " * List all tunnels."},
		{C_lookup, C_lookup + " * Lookup an i2p address."},
		{C_newkeys, C_newkeys + " * Generate a new keypair for the current nickname."},
		{C_option, C_option + " I2CPoption=something * Set an I2CP option. NOTE: Don't use any spaces."},
		{C_outhost, C_outhost + " hostname | IP * Set the outbound hostname or IP."},
		{C_outport, C_outport + " port_number * Set the outbound port that nickname contacts."},
		{C_quiet, C_quiet + " True | False * Don't send to the application the incoming destination."},
		{C_quit, C_quit + " * Quits this session with BOB."},
		{C_setkeys, C_setkeys + " BASE64_keypair * Sets the keypair for the current nickname."},
		{C_setnick, C_setnick + " nickname * Create a new nickname."},
		{C_show, C_show + " * Display the status of the current nickname."},
		{C_show_props, C_show_props + " * Display the properties of the current nickname."},
		{C_start, C_start + " * Start the current nickname tunnel."},
		{C_status, C_status + " nickname * Display status of a nicknamed tunnel."},
		{C_stop, C_stop + " * Stops the current nicknamed tunnel."},
		{C_verify, C_verify + " BASE64_key * Verifies BASE64 destination."},
		{C_visit, C_visit + " * Thread dump to wrapper.log."},
		{C_zap, C_zap + " * Shuts down BOB."},
		{"", "COMMANDS: " + // this is ugly, but...
			C_help + " " +
			C_clear + " " +
			C_getdest + " " +
			C_getkeys + " " +
			C_getnick + " " +
			C_inhost + " " +
			C_inport + " " +
			C_list + " " +
			C_lookup + " " +
			C_newkeys + " " +
			C_option + " " +
			C_outhost + " " +
			C_outport + " " +
			C_quiet + " " +
			C_quit + " " +
			C_setkeys + " " +
			C_setnick + " " +
			C_show + " " +
			C_show_props + " " +
			C_start + " " +
			C_status + " " +
			C_stop + " " +
			C_verify + " " +
			C_visit + " " +
			C_zap
		},
		{" ", " "} // end of list
	};

	/**
	 * @param LIVE
	 * @param server
	 * @param props
	 * @param database
	 * @param _log
	 */
	DoCMDS(AtomicBoolean LIVE, AtomicBoolean lock, Socket server, Properties props, NamedDB database, Logger _log) {
		this.lock = lock;
		this.LIVE = LIVE;
		this.server = server;
		this.props = new Properties();
		this.database = database;
		this._log = _log;
		Lifted.copyProperties(props, this.props);
	}

	private void rlock() {
		rlock(nickinfo);
	}

	private void rlock(NamedDB Arg) {
		database.getReadLock();
		Arg.getReadLock();
	}

	private void runlock() {
		runlock(nickinfo);
	}

	private void runlock(NamedDB Arg) {
		Arg.releaseReadLock();
		database.releaseReadLock();
	}

	private void wlock() {
		wlock(nickinfo);
	}

	private void wlock(NamedDB Arg) {
		database.getWriteLock();
		Arg.getWriteLock();
	}

	private void wunlock() {
		wunlock(nickinfo);
	}

	private void wunlock(NamedDB Arg) {
		Arg.releaseWriteLock();
		database.releaseWriteLock();
	}

	/**
	 * Try to print info from the database
	 *
	 * @param out
	 * @param info
	 * @param key
	 */
	private void trypnt(PrintStream out, NamedDB info, String key) {
		rlock(info);
		try {
			out.print(" " + key + ": ");
			if (info.exists(key)) {
				out.print(info.get(key));
			} else {
				out.print("not_set");
			}
		} finally {
			runlock(info);
		}
	}

	/**
	 * Print true or false if an object exists
	 *
	 * @param out
	 * @param info
	 * @param key
	 */
	private void tfpnt(PrintStream out, NamedDB info, String key) {
		rlock(info);
		try {
			out.print(" " + key + ": ");
			out.print(info.exists(key));
		} finally {
			runlock(info);
		}
	}

	/**
	 * Print an error message
	 *
	 * @param out
	 */
	private static void nns(PrintStream out) {
		out.println("ERROR no nickname has been set");
	}

	/**
	 * Dump various information from the database
	 *
	 * @param out
	 * @param info
	 */
	private void nickprint(PrintStream out, NamedDB info) {
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
	 * Dump properties information from the database
	 *
	 * @param out
	 * @param info
	 */
	private void propprint(PrintStream out, NamedDB info) {
		trypnt(out, info, P_PROPERTIES);
	}

	/**
	 * Print information on a specific record, indicated by NamedDB
	 * @param out
	 * @param Arg
	 */
	private void ttlpnt(PrintStream out, String Arg) {
		database.getReadLock();
		try {
			if (database.exists(Arg)) {
				out.print("DATA");
				nickprint(out, (NamedDB) database.get(Arg));
			}
		} finally {
			database.releaseReadLock();
		}
	}

	/**
	 * Is this NamedDB's tunnel active?
	 *
	 * @param Arg
	 * @return true if the tunnel is active
	 */
	private boolean tunnelactive(NamedDB Arg) {
		boolean retval;
		rlock(Arg);
		try {
			retval = (Arg.get(P_STARTING).equals(Boolean.TRUE) ||
				Arg.get(P_STOPPING).equals(Boolean.TRUE) ||
				Arg.get(P_RUNNING).equals(Boolean.TRUE));
		} finally {
			runlock();
		}
		return retval;
	}

	/**
	 * Does the base64 information look OK
	 *
	 * @param data
	 * @return OK
	 */
	private static boolean is64ok(String data) {
		try {
			new Destination(data);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * The actual parser.
	 * It probabbly needs a rewrite into functions, but I kind-of like inline code.
	 *
	 */
	public void run() {
		dk = ns = ip = op = false;
		try {
			try {
				// Get input from the client
				BufferedReader in = new BufferedReader(new InputStreamReader(server.getInputStream()));
				PrintStream out = new PrintStream(server.getOutputStream());
				quit:
				{
					die:
					{
						prikey = new ByteArrayOutputStream();
						out.println("BOB " + BOBversion);
						out.println("OK");
						while ((line = in.readLine()) != null) {
							StringTokenizer token = new StringTokenizer(line, " "); // use a space as a delimiter
							String Command = "";
							String Arg = "";
							NamedDB info;

							if (token.countTokens() != 0) {
								Command = token.nextToken();
								Command =
									Command.toLowerCase(Locale.US);
								if (token.countTokens() != 0) {
									Arg = token.nextToken();
								} else {
									Arg = "";
								}
								// The rest of the tokens are considered junk,
								// and discarded without any warnings.
								if (Command.equals(C_help)) {
									for (int i = 0; !C_ALL[i][0].equals(" "); i++) {
										if (C_ALL[i][0].equalsIgnoreCase(Arg)) {
											out.println("OK " + C_ALL[i][1]);
										}
									}
								} else if (Command.equals(C_visit)) {
									visitAllThreads();
									out.println("OK ");
								} else if (Command.equals(C_lookup)) {
									Destination dest = null;
									String reply = null;
									if (Arg.endsWith(".i2p")) {
										try {
											//try {
												//dest = I2PTunnel.destFromName(Arg);
											//} catch (DataFormatException ex) {
											//}
											dest = I2PAppContext.getGlobalContext().namingService().lookup(Arg);
											if(dest != null) {
												reply = dest.toBase64();
											}
										} catch (NullPointerException npe) {
											// Could not find the destination!?
										}
									}
									if (reply == null) {
										out.println("ERROR Address Not found.");
									} else {
										out.println("OK " + reply);
									}
								} else if (Command.equals(C_getdest)) {
									if (ns) {
										if (dk) {
											rlock();
											try {
												out.println("OK " + nickinfo.get(P_DEST));
											} catch (Exception e) {
												break die;
											} finally {
												runlock();
											}
										} else {
											out.println("ERROR keys not set.");
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_list)) {
									// Produce a formatted list of all nicknames
									database.getReadLock();
									try {
										for (Object ndb : database.values()) {
											try {
												info = (NamedDB) ndb;
												out.print("DATA");
											} catch (Exception e) {
												break die;
											}
											nickprint(out, info);
										}
									} finally {
										database.releaseReadLock();
									}
									out.println("OK Listing done");
								} else if (Command.equals(C_quit)) {
									// End the command session
									break quit;
								} else if (Command.equals(C_zap)) {
									// Kill BOB!! (let's hope this works!)
									LIVE.set(false);
									// End the command session
									break quit;
								} else if (Command.equals(C_newkeys)) {
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												try {
													// Make a new PublicKey and PrivateKey
													prikey = new ByteArrayOutputStream();
													d = I2PClientFactory.createClient().createDestination(prikey);
													wlock();
													try {
														nickinfo.add(P_KEYS, prikey.toByteArray());
														nickinfo.add(P_DEST, d.toBase64());
														out.println("OK " + nickinfo.get(P_DEST));
													} catch (Exception e) {
														break die;
													} finally {
														wunlock();
													}
													dk = true;
												} catch (I2PException ipe) {
													_log.error("Error generating keys", ipe);
													out.println("ERROR generating keys");
												}
											}
										} catch (Exception e) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_getkeys)) {
									// Return public key
									if (dk) {
										prikey = new ByteArrayOutputStream();
										rlock();
										try {
											prikey.write(((byte[]) nickinfo.get(P_KEYS)));
										} catch (Exception ex) {
											break die;
										} finally {
											runlock();
										}
										out.println("OK " + net.i2p.data.Base64.encode(prikey.toByteArray()));
									} else {
										out.println("ERROR no public key has been set");
									}

								} else if (Command.equals(C_quiet)) {
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												wlock();
												try {
													nickinfo.add(P_QUIET, Boolean.valueOf(Arg));
												} catch (Exception ex) {
													break die;
												} finally {
													wunlock();
												}
												out.println("OK Quiet set");
											}
										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_verify)) {
									if (is64ok(Arg)) {
										out.println("OK");
									} else {
										out.println("ERROR not in BASE64 format");
									}
								} else if (Command.equals(C_setkeys)) {
									// Set the NamedDB to a privatekey in BASE64 format
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												try {
													prikey = new ByteArrayOutputStream();
													prikey.write(net.i2p.data.Base64.decode(Arg));
													d = new Destination();
													d.fromBase64(Arg);
												} catch (Exception ex) {
													Arg = "";
												}

												if ((Arg.length() == 884) && is64ok(Arg)) {
													wlock();
													try {
														nickinfo.add(P_KEYS, prikey.toByteArray());
														nickinfo.add(P_DEST, d.toBase64());
														out.println("OK " + nickinfo.get(P_DEST));
													} catch (Exception ex) {
														break die;
													} finally {
														wunlock();
													}
													dk = true;
												} else {
													out.println("ERROR not in BASE64 format");
												}
											}
										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_setnick)) {
									ns = dk = ip = op = false;
									database.getReadLock();
									try {
										nickinfo = (NamedDB) database.get(Arg);
										if (!tunnelactive(nickinfo)) {
											nickinfo = null;
											ns = true;
										}

									} catch (Exception b) {
										nickinfo = null;
										ns = true;
									} finally {
										database.releaseReadLock();
									}
									// Clears and Sets the initial NamedDB structure to work with
									if (ns) {
										nickinfo = new NamedDB();
										wlock();
										try {
											database.add(Arg, nickinfo);
											nickinfo.add(P_NICKNAME, Arg);
											nickinfo.add(P_STARTING, Boolean.FALSE);
											nickinfo.add(P_RUNNING, Boolean.FALSE);
											nickinfo.add(P_STOPPING, Boolean.FALSE);
											nickinfo.add(P_QUIET, Boolean.FALSE);
											nickinfo.add(P_INHOST, "localhost");
											nickinfo.add(P_OUTHOST, "localhost");
											Properties Q = new Properties();
											Lifted.copyProperties(this.props, Q);
											Q.setProperty("inbound.nickname", Arg);
											Q.setProperty("outbound.nickname", Arg);
											nickinfo.add(P_PROPERTIES, Q);
										} catch (Exception e) {
											break die;
										} finally {
											wunlock();
										}
										out.println("OK Nickname set to " + Arg);
									} else {
										out.println("ERROR tunnel is active");
									}

								} else if (Command.equals(C_option)) {
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												StringTokenizer otoken = new StringTokenizer(Arg, "="); // use an equal sign as a delimiter
												if (otoken.countTokens() != 2) {
													out.println("ERROR too many or no options.");
												} else {
													String pname = otoken.nextToken();
													String pval = otoken.nextToken();
													wlock();
													try {
														Properties Q = (Properties) nickinfo.get(P_PROPERTIES);
														Q.setProperty(pname, pval);
														nickinfo.add(P_PROPERTIES, Q);
													} catch (Exception ex) {
														break die;
													} finally {
														wunlock();
													}
													out.println("OK " + pname + " set to " + pval);
												}
											}
										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_getnick)) {
									// Get the NamedDB to work with...
									boolean nsfail = false;
									database.getReadLock();
									try {
										nickinfo = (NamedDB) database.get(Arg);
										ns = true;
									} catch (RuntimeException b) {
										nsfail = true;
										nns(out);
									} finally {
										database.releaseReadLock();
									}
									if (ns && !nsfail) {
										rlock();
										try {
											dk = nickinfo.exists(P_KEYS);
											ip = nickinfo.exists(P_INPORT);
											op = nickinfo.exists(P_OUTPORT);
										} catch (Exception ex) {
											break die;
										} finally {
											runlock();
										}
										// Finally say OK.
										out.println("OK Nickname set to " + Arg);
									}

								} else if (Command.equals(C_inport)) {
									// Set the NamedDB inbound TO the router port
									// app --> BOB
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												int prt;
												wlock();
												try {
													nickinfo.kill(P_INPORT);
													prt = Integer.parseInt(Arg);
													if (prt > 1 && prt < 65536) {
														try {
															nickinfo.add(P_INPORT, Integer.valueOf(prt));
														} catch (Exception ex) {
															break die;
														}
													}
													ip = nickinfo.exists(P_INPORT);
												} catch (NumberFormatException nfe) {
													out.println("ERROR not a number");
												} finally {
													wunlock();
												}
												if (ip) {
													out.println("OK inbound port set");
												} else {
													out.println("ERROR port out of range");
												}
											}
										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_outport)) {
									// Set the NamedDB outbound FROM the router port
									// BOB --> app
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												int prt;
												wlock();
												try {
													nickinfo.kill(P_OUTPORT);
													prt = Integer.parseInt(Arg);
													if (prt > 1 && prt < 65536) {
														nickinfo.add(P_OUTPORT, Integer.valueOf(prt));
													}
													ip = nickinfo.exists(P_OUTPORT);
												} catch (NumberFormatException nfe) {
													out.println("ERROR not a number");
												} catch (Exception ex) {
													break die;
												} finally {
													wunlock();
												}
												if (ip) {
													out.println("OK outbound port set");
												} else {
													out.println("ERROR port out of range");
												}
											}
										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_inhost)) {
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												wlock();
												try {
													nickinfo.add(P_INHOST, Arg);
												} catch (Exception ex) {
													break die;
												} finally {
													wunlock();
												}
												out.println("OK inhost set");
											}
										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_outhost)) {
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												wlock();
												try {
													nickinfo.add(P_OUTHOST, Arg);
												} catch (Exception ex) {
													break die;
												} finally {
													wunlock();
												}
												out.println("OK outhost set");
											}
										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_show)) {
									// Get the current NamedDB properties
									if (ns) {
										out.print("OK");
										nickprint(out, nickinfo);
									} else {
										nns(out);
									}

								} else if (Command.equals(C_show_props)) {
									// Get the current options properties
									if (ns) {
										out.print("OK");
										propprint(out, nickinfo);
									} else {
										nns(out);
									}

								} else if (Command.equals(C_start)) {
									// Start the tunnel, if we have all the information
									if (ns && dk && (ip || op)) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												MUXlisten tunnel;
												try {
													while (!lock.compareAndSet(false, true)) {
														// wait
													}
													tunnel = new MUXlisten(lock, database, nickinfo, _log);
													Thread t = new I2PAppThread(tunnel);
													t.start();
													// try {
													//	Thread.sleep(1000 * 10); // Slow down the startup.
													// } catch(InterruptedException ie) {
													//	// ignore it
													// }
													out.println("OK tunnel starting");
												} catch (I2PException e) {
													lock.set(false);
													out.println("ERROR starting tunnel: " + e);
												} catch (IOException e) {
													lock.set(false);
													out.println("ERROR starting tunnel: " + e);
												}
											}
										} catch (Exception ex) {
											break die;
										}

									} else {
										out.println("ERROR tunnel settings incomplete");
									}

								} else if (Command.equals(C_stop)) {
									// Stop the tunnel, if it is running
									if (ns) {
										rlock();
										boolean released = false;
										try {
											if (nickinfo.get(P_RUNNING).equals(Boolean.TRUE) && nickinfo.get(P_STOPPING).equals(Boolean.FALSE) && nickinfo.get(P_STARTING).equals(Boolean.FALSE)) {
												runlock();
												released = true;
												wlock();
												try {
													nickinfo.add(P_STOPPING, Boolean.TRUE);
												} catch (Exception e) {
													break die;
												} finally {
													wunlock();
												}
												out.println("OK tunnel stopping");
											} else {
												out.println("ERROR tunnel is inactive");
											}
										} catch (Exception e) {
											break die;
										} finally {
											if (!released)
												runlock();
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_clear)) {
									// Clear use of the NamedDB if stopped
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												database.getWriteLock();
												try {
													database.kill((String) nickinfo.get(P_NICKNAME));
												} catch (Exception e) {
												} finally {
													database.releaseWriteLock();
												}
												dk = ns = ip = op = false;
												out.println("OK cleared");
											}

										} catch (Exception ex) {
											break die;
										}
									} else {
										nns(out);
									}

								} else if (Command.equals(C_status)) {
									database.getReadLock();
									try {
										if (database.exists(Arg)) {
											// Show status of a NamedDB
											out.print("OK ");
											try {
												ttlpnt(out, Arg);
											} catch (Exception e) {
												out.println(); // this will cause an IOE if IOE
												break die;
											}
										} else {
											nns(out);
										}
									} catch (Exception e) {
										break die;
									} finally {
										database.releaseReadLock();
									}
								} else {
									out.println("ERROR UNKNOWN COMMAND! Try help");
								}

							}
						}
					} // die
					out.print("ERROR A really bad error just happened, ");
				} // quit
				// Say goodbye.

				out.println("OK Bye!");

			} catch (IOException ioe) {
				// not really needed, except to debug.
				// BOB.warn("IOException on socket listen: " + ioe);
				// ioe.printStackTrace();
			}
		} finally {
			try {
				server.close();
			} catch (IOException ex) {
				// nop
			}
		}
	}
	// Debugging... None of this is normally used.

	/**
	 *	Find the root thread group and print them all.
	 *
	 */
	private void visitAllThreads() {
		ThreadGroup root = Thread.currentThread().getThreadGroup().getParent();
		while (root.getParent() != null) {
			root = root.getParent();
		}

		// Visit each thread group
		visit(root, 0, root.getName());
	}

	/**
	 * Recursively visits all thread groups under `group' and dumps them.
	 * @param group ThreadGroup to visit
	 * @param level Current level
	 */
	private static void visit(ThreadGroup group, int level, String tn) {
		// Get threads in `group'
		int numThreads = group.activeCount();
		Thread[] threads = new Thread[numThreads * 2];
		numThreads = group.enumerate(threads, false);
		String indent = "------------------------------------".substring(0, level) + "-> ";
		// Enumerate each thread in `group' and print it.
		for (int i = 0; i < numThreads; i++) {
			// Get thread
			Thread thread = threads[i];
			System.out.println("BOB: " + indent + tn + ": " + thread.toString());
		}

		// Get thread subgroups of `group'
		int numGroups = group.activeGroupCount();
		ThreadGroup[] groups = new ThreadGroup[numGroups * 2];
		numGroups = group.enumerate(groups, false);

		// Recursively visit each subgroup
		for (int i = 0; i < numGroups; i++) {
			visit(groups[i], level + 1, groups[i].getName());
		}
	}
}
