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
import java.util.concurrent.atomic.AtomicBoolean;
import net.i2p.I2PException;
import net.i2p.client.I2PClientFactory;
import net.i2p.data.Destination;
import net.i2p.util.Log;
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
	public static final String BMAJ = "00",  BMIN = "00",  BREV = "08",  BEXT = "";
	public static final String BOBversion = BMAJ + "." + BMIN + "." + BREV + BEXT;
	private Socket server;
	private Properties props;
	private NamedDB database;
	private String line;
	private Destination d;
	private ByteArrayOutputStream prikey;
	private boolean dk,  ns,  ip,  op;
	private NamedDB nickinfo;
	private Log _log;
	private AtomicBoolean LIVE;
	private AtomicBoolean lock;
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
	private static final String C_show_props = "showprops";
	private static final String C_start = "start";
	private static final String C_status = "status";
	private static final String C_stop = "stop";
	private static final String C_verify = "verify";
	private static final String C_visit = "visit";
	private static final String C_zap = "zap";

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
	DoCMDS(AtomicBoolean LIVE, AtomicBoolean lock, Socket server, Properties props, NamedDB database, Log _log) {
		this.lock = lock;
		this.LIVE = LIVE;
		this.server = server;
		this.props = new Properties();
		this.database = database;
		this._log = _log;
		Lifted.copyProperties(props, this.props);
	}

	private void rlock() throws Exception {
		rlock(nickinfo);
	}

	private void rlock(NamedDB Arg) throws Exception {
		database.getReadLock();
		Arg.getReadLock();
	}

	private void runlock() throws Exception {
		runlock(nickinfo);
	}

	private void runlock(NamedDB Arg) throws Exception {
		Arg.releaseReadLock();
		database.releaseReadLock();
	}

	private void wlock() throws Exception {
		wlock(nickinfo);
	}

	private void wlock(NamedDB Arg) throws Exception {
		database.getWriteLock();
		Arg.getWriteLock();
	}

	private void wunlock() throws Exception {
		wunlock(nickinfo);
	}

	private void wunlock(NamedDB Arg) throws Exception {
		Arg.releaseWriteLock();
		database.releaseWriteLock();
	}

	/**
	 * Try to print info from the database
	 *
	 * @param out
	 * @param info
	 * @param key
	 * @throws Exception
	 */
	private void trypnt(PrintStream out, NamedDB info, Object key) throws Exception {
		try {
			rlock(info);
		} catch (Exception e) {
			throw new Exception(e);
		}
		try {
			out.print(" " + key + ": ");
			if (info.exists(key)) {
				out.print(info.get(key));
			} else {
				out.print("not_set");
			}
		} catch (Exception e) {
			runlock(info);
			throw new Exception(e);
		}
		runlock(info);
	}

	/**
	 * Print true or false if an object exists
	 *
	 * @param out
	 * @param info
	 * @param key
	 * @throws Exception
	 */
	private void tfpnt(PrintStream out, NamedDB info, Object key) throws Exception {
		try {
			rlock(info);
		} catch (Exception e) {
			throw new Exception(e);
		}
		try {
			out.print(" " + key + ": ");
			out.print(info.exists(key));
		} catch (Exception e) {
			runlock(info);
			throw new Exception(e);
		}
		runlock(info);
	}

	/**
	 * Print an error message
	 *
	 * @param out
	 */
	private void nns(PrintStream out) throws IOException {
		out.println("ERROR no nickname has been set");
	}

	/**
	 * Dump various information from the database
	 *
	 * @param out
	 * @param info
	 * @throws Exception
	 */
	private void nickprint(PrintStream out, NamedDB info) throws Exception {
		try {
			rlock(info);
		} catch (Exception e) {
			throw new Exception(e);
		}
		try {

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
		} catch (Exception e) {
			runlock(info);
			throw new Exception(e);
		}

		runlock(info);
	}

	/**
	 * Dump properties information from the database
	 *
	 * @param out
	 * @param info
	 * @throws Exception
	 */
	private void propprint(PrintStream out, NamedDB info) throws Exception {
		try {
			rlock(info);
		} catch (Exception e) {
			throw new Exception(e);
		}
		try {

			trypnt(out, info, P_PROPERTIES);
			out.println();
		} catch (Exception e) {
			runlock(info);
			throw new Exception(e);
		}

		runlock(info);
	}

	/**
	 * Print information on a specific record, indicated by NamedDB
	 * @param out
	 * @param Arg
	 * @throws Exception
	 */
	private void ttlpnt(PrintStream out, Object Arg) throws Exception {
		try {
			database.getReadLock();
		} catch (Exception e) {
			throw new Exception(e);
		}

		try {
			if (database.exists(Arg)) {
				out.print("DATA");
				nickprint(out, (NamedDB) database.get(Arg));
			}
		} catch (Exception e) {
			database.releaseReadLock();
			throw new Exception(e);
		}


		database.releaseReadLock();
	}

	/**
	 * Is this NamedDB's tunnel active?
	 *
	 * @param Arg
	 * @return true if the tunnel is active
	 */
	private boolean tunnelactive(NamedDB Arg) throws Exception {
		boolean retval;
		try {
			rlock(Arg);
		} catch (Exception e) {
			throw new Exception(e);
		}

		try {
			retval = (Arg.get(P_STARTING).equals(Boolean.TRUE) ||
				Arg.get(P_STOPPING).equals(Boolean.TRUE) ||
				Arg.get(P_RUNNING).equals(Boolean.TRUE));
		} catch (Exception e) {
			runlock();
			throw new Exception(e);
		}

		runlock(Arg);
		return retval;
	}

	/**
	 * Does the base64 information look OK
	 *
	 * @param data
	 * @return
	 */
	private boolean is64ok(String data) {
		try {
			Destination x = new Destination(data);
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
									Command.toLowerCase();
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
								} else if (Command.equals(C_getdest)) {
									if (ns) {
										if (dk) {
											try {
												rlock();
											} catch (Exception ex) {
												break die;
											}

											try {
												out.println("OK " + nickinfo.get(P_DEST));
											} catch (Exception e) {
												try {
													runlock();
												} catch (Exception ex) {
													break die;
												}
												break die;
											}

											try {
												runlock();
											} catch (Exception ex) {
												break die;
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
									for (int i = 0; i <
										database.getcount(); i++) {
										try {
											info = (NamedDB) database.getnext(i);
											out.print("DATA");
										} catch (Exception e) {
											database.releaseReadLock();
											break die;
										}

										try {
											info.getReadLock();
										} catch (Exception ex) {
											break die;
										}
										try {
											nickprint(out, info);
										} catch (Exception e) {
											try {
												info.releaseReadLock();
												database.releaseReadLock();
											} catch (Exception ex) {
												break die;
											}
											break die;
										}

										try {
											info.releaseReadLock();
										} catch (Exception ex) {
											break die;
										}
									}

									try {
										database.releaseReadLock();
									} catch (Exception ex) {
										break die;
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
													try {
														wlock();
													} catch (Exception e) {
														break die;
													}

													try {
														nickinfo.add(P_KEYS, prikey.toByteArray());
														nickinfo.add(P_DEST, d.toBase64());
													} catch (Exception e) {
														try {
															wunlock();
														} catch (Exception ex) {
															break die;
														}
														break die;
													}

													dk = true;
													try {
														wunlock();
													} catch (Exception ex) {
														break die;
													}

													try {
														rlock();
													} catch (Exception ex) {
														break die;
													}

													try {
														out.println("OK " + nickinfo.get(P_DEST));
													} catch (Exception e) {
														runlock();
														break die;
													}

													try {
														runlock();
													} catch (Exception ex) {
														break die;
													}
												} catch (I2PException ipe) {
													BOB.error("Error generating keys" + ipe);
													out.println("ERROR generating keys");
												}

											}
										} catch (Exception e) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception ex) {
											break die;
										}
									}

								} else if (Command.equals(C_getkeys)) {
									// Return public key
									if (dk) {
										prikey = new ByteArrayOutputStream();
										try {
											rlock();
										} catch (Exception e) {
											break die;
										}
										try {
											prikey.write(((byte[]) nickinfo.get(P_KEYS)));
										} catch (Exception ex) {
											try {
												runlock();
											} catch (Exception ee) {
												break die;
											}
											break die;
										}
										try {
											runlock();
										} catch (Exception e) {
											break die;
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
												try {
													wlock();
												} catch (Exception ex) {
													break die;
												}
												try {
													nickinfo.add(P_QUIET, new Boolean(Boolean.parseBoolean(Arg) == true));
												} catch (Exception ex) {
													try {
														wunlock();
													} catch (Exception ee) {
														break die;
													}
													break die;
												}

												try {
													wunlock();
												} catch (Exception ex) {
													break die;
												}

												out.println("OK Quiet set");
											}

										} catch (Exception ex) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception ex) {
											break die;
										}
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
													d.fromBase64(Arg);
												} catch (Exception ex) {
													Arg = "";
												}

												if ((Arg.length() == 884) && is64ok(Arg)) {
													try {
														wlock();
													} catch (Exception ex) {
														break die;
													}
													try {
														nickinfo.add(P_KEYS, prikey.toByteArray());
														nickinfo.add(P_DEST, d.toBase64());
													} catch (Exception ex) {
														try {
															wunlock();
														} catch (Exception ee) {
															break die;
														}
														break die;
													}
													dk = true;
													try {
														wunlock();
													} catch (Exception ex) {
														break die;
													}

													try {
														rlock();
													} catch (Exception ex) {
														break die;
													}

													try {
														out.println("OK " + nickinfo.get(P_DEST));
													} catch (Exception e) {
														try {
															runlock();
														} catch (Exception ex) {
															break die;
														}
														break die;
													}

													try {
														runlock();
													} catch (Exception ex) {
														break die;
													}
												} else {
													out.println("ERROR not in BASE64 format");
												}

											}
										} catch (Exception ex) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception ex) {
											break die;
										}
									}

								} else if (Command.equals(C_setnick)) {
									ns = dk = ip = op = false;
									try {
										database.getReadLock();
									} catch (Exception ex) {
										break die;
									}
									try {
										nickinfo = (NamedDB) database.get(Arg);
										if (!tunnelactive(nickinfo)) {
											nickinfo = null;
											ns = true;
										}

									} catch (Exception b) {
										nickinfo = null;
										ns = true;
									}

									try {
										database.releaseReadLock();
									} catch (Exception ex) {
										break die;
									}
									// Clears and Sets the initial NamedDB structure to work with
									if (ns) {
										nickinfo = new NamedDB();
										try {
											wlock();
										} catch (Exception e) {
											break die;
										}

										try {
											database.add(Arg, nickinfo);
											nickinfo.add(P_NICKNAME, Arg);
											nickinfo.add(P_STARTING, new Boolean(false));
											nickinfo.add(P_RUNNING, new Boolean(false));
											nickinfo.add(P_STOPPING, new Boolean(false));
											nickinfo.add(P_QUIET, new Boolean(false));
											nickinfo.add(P_INHOST, "localhost");
											nickinfo.add(P_OUTHOST, "localhost");
											Properties Q = new Properties();
											Lifted.copyProperties(this.props, Q);
											Q.setProperty("inbound.nickname", Arg);
											Q.setProperty("outbound.nickname", Arg);
											nickinfo.add(P_PROPERTIES, Q);
										} catch (Exception e) {
											try {
												wunlock();
												break die;
											} catch (Exception ee) {
												break die;
											}

										}
										try {
											wunlock();
										} catch (Exception e) {
											break die;
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
													out.println("ERROR to many or no options.");
												} else {
													String pname = otoken.nextToken();
													String pval = otoken.nextToken();
													try {
														rlock();
													} catch (Exception ex) {
														break die;
													}

													Properties Q = (Properties) nickinfo.get(P_PROPERTIES);
													try {
														runlock();
													} catch (Exception ex) {
														break die;
													}

													Q.setProperty(pname, pval);
													try {
														wlock();
													} catch (Exception ex) {
														break die;
													}

													try {
														nickinfo.add(P_PROPERTIES, Q);
													} catch (Exception ex) {
														try {
															wunlock();
														} catch (Exception ee) {
															break die;
														}
														break die;
													}
													try {
														wunlock();
													} catch (Exception ex) {
														break die;
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
									try {
										database.getReadLock();
									} catch (Exception ex) {
										break die;
									}
									try {
										nickinfo = (NamedDB) database.get(Arg);
										ns = true;
									} catch (RuntimeException b) {
										try {
											nns(out);
										} catch (Exception ex) {
											try {
												database.releaseReadLock();
											} catch (Exception ee) {
												break die;
											}
											break die;
										}
									}

									database.releaseReadLock();
									if (ns) {
										try {
											rlock();
										} catch (Exception e) {
											break die;
										}
										try {
											dk = nickinfo.exists(P_KEYS);
											ip = nickinfo.exists(P_INPORT);
											op = nickinfo.exists(P_OUTPORT);
										} catch (Exception ex) {
											try {
												runlock();
											} catch (Exception ee) {
												break die;
											}
											break die;
										}
										try {
											runlock();
										} catch (Exception e) {
											break die;
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
												try {
													wlock();
												} catch (Exception ex) {
													break die;
												}

												try {
													nickinfo.kill(P_INPORT);
												} catch (Exception ex) {
													try {
														wunlock();
													} catch (Exception ee) {
														break die;
													}

													break die;
												}
												try {
													prt = Integer.parseInt(Arg);
													if (prt > 1 && prt < 65536) {
														try {
															nickinfo.add(P_INPORT, new Integer(prt));
														} catch (Exception ex) {
															try {
																wunlock();
															} catch (Exception ee) {
																break die;
															}

															break die;
														}
													}

												} catch (NumberFormatException nfe) {
													out.println("ERROR not a number");
												}

												try {
													wunlock();
												} catch (Exception ex) {
													break die;
												}
												try {
													rlock();
												} catch (Exception ex) {
													break die;
												}

												try {
													ip = nickinfo.exists(P_INPORT);
												} catch (Exception ex) {
													try {
														runlock();
													} catch (Exception ee) {
														break die;
													}
													break die;
												}
												try {
													runlock();
												} catch (Exception ex) {
													break die;
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
												try {
													wlock();
												} catch (Exception ex) {
													break die;
												}

												try {
													nickinfo.kill(P_OUTPORT);
												} catch (Exception ex) {
													try {
														wunlock();
													} catch (Exception ee) {
														break die;
													}
													break die;
												}
												try {
													prt = Integer.parseInt(Arg);
													if (prt > 1 && prt < 65536) {
														try {
															nickinfo.add(P_OUTPORT, new Integer(prt));
														} catch (Exception ex) {
															try {
																wunlock();
															} catch (Exception ee) {
																break die;
															}
															break die;
														}
													}

												} catch (NumberFormatException nfe) {
													out.println("ERROR not a number");
												}

												try {
													wunlock();
												} catch (Exception ex) {
													break die;
												}
												try {
													rlock();
												} catch (Exception ex) {
													break die;
												}

												try {
													ip = nickinfo.exists(P_OUTPORT);
												} catch (Exception ex) {
													try {
														runlock();
													} catch (Exception ee) {
														break die;
													}
													break die;
												}
												try {
													runlock();
												} catch (Exception ex) {
													break die;
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
										try {
											nns(out);
										} catch (Exception ex) {
											break die;
										}
									}

								} else if (Command.equals(C_inhost)) {
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												try {
													wlock();
												} catch (Exception ex) {
													break die;
												}
												try {
													nickinfo.add(P_INHOST, Arg);
												} catch (Exception ex) {
													try {
														wunlock();
													} catch (Exception ee) {
														break die;
													}
													break die;
												}
												try {
													wunlock();
												} catch (Exception ex) {
													break die;
												}

												out.println("OK inhost set");
											}

										} catch (Exception ex) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception ex) {
											break die;
										}
									}

								} else if (Command.equals(C_outhost)) {
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												try {
													wlock();
												} catch (Exception ex) {
													break die;
												}
												try {
													nickinfo.add(P_OUTHOST, Arg);
												} catch (Exception ex) {
													try {
														wunlock();
													} catch (Exception ee) {
														break die;
													}
													break die;
												}
												try {
													wunlock();
												} catch (Exception ex) {
													break die;
												}

												out.println("OK outhost set");
											}

										} catch (Exception ex) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception ex) {
											break die;
										}
									}

								} else if (Command.equals(C_show)) {
									// Get the current NamedDB properties
									if (ns) {
										out.print("OK");
										try {
											rlock();
										} catch (Exception e) {
											break die;
										}

										try {
											nickprint(out, nickinfo);
										} catch (Exception e) {
											try {
												runlock();
											} catch (Exception ee) {
												break die;
											}

											out.println(); // this will cause an IOE if IOE
											break die;
										}

										try {
											runlock();
										} catch (Exception e) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception e) {
											break die;
										}
									}

								} else if (Command.equals(C_show_props)) {
									// Get the current options properties
									if (ns) {
										out.print("OK");
										try {
											rlock();
										} catch (Exception e) {
											break die;
										}

										try {
											propprint(out, nickinfo);
										} catch (Exception e) {
											try {
												runlock();
											} catch (Exception ee) {
												break die;
											}

											out.println(); // this will cause an IOE if IOE
											break die;
										}

										try {
											runlock();
										} catch (Exception e) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception e) {
											break die;
										}
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
													Thread t = new Thread(tunnel);
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
										try {
											rlock();
										} catch (Exception e) {
											break die;
										}

										try {
											if (nickinfo.get(P_RUNNING).equals(Boolean.TRUE) && nickinfo.get(P_STOPPING).equals(Boolean.FALSE) && nickinfo.get(P_STARTING).equals(Boolean.FALSE)) {
												try {
													runlock();
												} catch (Exception e) {
													break die;
												}

												try {
													wlock();
												} catch (Exception e) {
													break die;
												}

												nickinfo.add(P_STOPPING, new Boolean(true));
												try {
													wunlock();

												} catch (Exception e) {
													break die;
												}

												out.println("OK tunnel stopping");
											} else {
												try {
													runlock();
												} catch (Exception e) {
													break die;
												}

												out.println("ERROR tunnel is inactive");
											}
										} catch (Exception e) {
											try {
												runlock();
											} catch (Exception ee) {
												break die;
											}
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception e) {
											break die;
										}
									}

								} else if (Command.equals(C_clear)) {
									// Clear use of the NamedDB if stopped
									if (ns) {
										try {
											if (tunnelactive(nickinfo)) {
												out.println("ERROR tunnel is active");
											} else {
												try {
													database.getWriteLock();
												} catch (Exception e) {
													break die;
												}
												try {
													database.kill(nickinfo.get(P_NICKNAME));
												} catch (Exception e) {
													try {
														database.releaseWriteLock();
													} catch (Exception ee) {
														break die;
													}
													break die;
												}
												try {
													database.releaseWriteLock();
												} catch (Exception e) {
													break die;
												}
												dk = ns = ip = op = false;
												out.println("OK cleared");
											}

										} catch (Exception ex) {
											break die;
										}

									} else {
										try {
											nns(out);
										} catch (Exception e) {
											break die;
										}
									}

								} else if (Command.equals(C_status)) {
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
											try {
												nns(out);
											} catch (Exception e) {
												break die;
											}
										}
									} catch (Exception e) {
										break die;
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
