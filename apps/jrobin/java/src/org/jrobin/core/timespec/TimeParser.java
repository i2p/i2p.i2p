/*******************************************************************************
 * Copyright (c) 2001-2005 Sasa Markovic and Ciaran Treanor.
 * Copyright (c) 2011 The OpenNMS Group, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 *******************************************************************************/

/*
 * Java port of Tobi's original parsetime.c routine
 */
package org.jrobin.core.timespec;

import org.jrobin.core.RrdException;
import org.jrobin.core.Util;

/**
 * Class which parses at-style time specification (described in detail on the rrdfetch man page),
 * used in all RRDTool commands. This code is in most parts just a java port of Tobi's parsetime.c
 * code.
 */
public class TimeParser {
	private static final int PREVIOUS_OP = -1;

	TimeToken token;
	TimeScanner scanner;
	TimeSpec spec;

	int op = TimeToken.PLUS;
	int prev_multiplier = -1;

	/**
	 * Constructs TimeParser instance from the given input string.
	 *
	 * @param dateString at-style time specification (read rrdfetch man page
	 *                   for the complete explanation)
	 */
	public TimeParser(String dateString) {
		scanner = new TimeScanner(dateString);
		spec = new TimeSpec(dateString);
	}

	private void expectToken(int desired, String errorMessage) throws RrdException {
		token = scanner.nextToken();
		if (token.id != desired) {
			throw new RrdException(errorMessage);
		}
	}

	private void plusMinus(int doop) throws RrdException {
		if (doop >= 0) {
			op = doop;
			expectToken(TimeToken.NUMBER, "There should be number after " +
					(op == TimeToken.PLUS ? '+' : '-'));
			prev_multiplier = -1; /* reset months-minutes guessing mechanics */
		}
		int delta = Integer.parseInt(token.value);
		token = scanner.nextToken();
		if (token.id == TimeToken.MONTHS_MINUTES) {
			/* hard job to guess what does that -5m means: -5mon or -5min? */
			switch (prev_multiplier) {
				case TimeToken.DAYS:
				case TimeToken.WEEKS:
				case TimeToken.MONTHS:
				case TimeToken.YEARS:
					token = scanner.resolveMonthsMinutes(TimeToken.MONTHS);
					break;
				case TimeToken.SECONDS:
				case TimeToken.MINUTES:
				case TimeToken.HOURS:
					token = scanner.resolveMonthsMinutes(TimeToken.MINUTES);
					break;
				default:
					if (delta < 6) {
						token = scanner.resolveMonthsMinutes(TimeToken.MONTHS);
					}
					else {
						token = scanner.resolveMonthsMinutes(TimeToken.MINUTES);
					}
			}
		}
		prev_multiplier = token.id;
		delta *= (op == TimeToken.PLUS) ? +1 : -1;
		switch (token.id) {
			case TimeToken.YEARS:
				spec.dyear += delta;
				break;
			case TimeToken.MONTHS:
				spec.dmonth += delta;
				break;
			case TimeToken.WEEKS:
				delta *= 7;
				spec.dday += delta;
				break;
			case TimeToken.DAYS:
				spec.dday += delta;
				break;
			case TimeToken.HOURS:
				spec.dhour += delta;
				break;
			case TimeToken.MINUTES:
				spec.dmin += delta;
				break;
			case TimeToken.SECONDS:
			default: // default is 'seconds'
				spec.dsec += delta;
				break;
		}
		// unreachable statement
		// throw new RrdException("Well-known time unit expected after " + delta);
	}

	/**
	 * Try and read a "timeofday" specification.  This method will be called
	 * when we see a plain number at the start of a time, which means we could be
	 * reading a time, or a day.  If it turns out to be a date, then this method restores
	 * the scanner state to what it was at entry, and returns without setting anything.
	 * @throws RrdException
	 */
	private void timeOfDay() throws RrdException {
		int hour, minute = 0;
		/* save token status in case we must abort */
		scanner.saveState();
		/* first pick out the time of day - we assume a HH (COLON|DOT) MM time */
		if (token.value.length() > 2) {
			//Definitely not an hour specification; probably a date or something.  Give up now
			return;
		}
		hour = Integer.parseInt(token.value);
		token = scanner.nextToken();
		if (token.id == TimeToken.SLASH) {
			/* guess we are looking at a date */
			token = scanner.restoreState();
			return;
		}
		if (token.id == TimeToken.COLON || token.id == TimeToken.DOT) {
			expectToken(TimeToken.NUMBER, "Parsing HH:MM or HH.MM syntax, expecting MM as number, got none");
			minute = Integer.parseInt(token.value);
			if (minute > 59) {
				throw new RrdException("Parsing HH:MM or HH.MM syntax, got MM = " +
						minute + " (>59!)");
			}
			token = scanner.nextToken();
			if(token.id == TimeToken.DOT) {
				//Oh look, another dot; must have actually been a date in DD.MM.YYYY format.  Give up and return
				token = scanner.restoreState();
				return;
			}

		}
		/* check if an AM or PM specifier was given */
		if (token.id == TimeToken.AM || token.id == TimeToken.PM) {
			if (hour > 12) {
				throw new RrdException("There cannot be more than 12 AM or PM hours");
			}
			if (token.id == TimeToken.PM) {
				if (hour != 12) {
					/* 12:xx PM is 12:xx, not 24:xx */
					hour += 12;
				}
			}
			else {
				if (hour == 12) {
					/* 12:xx AM is 00:xx, not 12:xx */
					hour = 0;
				}
			}
			token = scanner.nextToken();
		}
		else if (hour > 23) {
			/* guess it was not a time then, probably a date ... */
			token = scanner.restoreState();
			return;
		}

		spec.hour = hour;
		spec.min = minute;
		spec.sec = 0;
		if (spec.hour == 24) {
			spec.hour = 0;
			spec.day++;
		}
	}

	private void assignDate(long mday, long mon, long year) throws RrdException {
		if (year > 138) {
			if (year > 1970) {
				year -= 1900;
			}
			else {
				throw new RrdException("Invalid year " + year +
						" (should be either 00-99 or >1900)");
			}
		}
		else if (year >= 0 && year < 38) {
			year += 100;		 /* Allow year 2000-2037 to be specified as   */
		}						 /* 00-37 until the problem of 2038 year will */
		/* arise for unices with 32-bit time_t     */
		if (year < 70) {
			throw new RrdException("Won't handle dates before epoch (01/01/1970), sorry");
		}
		spec.year = (int) year;
		spec.month = (int) mon;
		spec.day = (int) mday;
	}

	private void day() throws RrdException {
		long mday = 0, wday, mon, year = spec.year;
		switch (token.id) {
			case TimeToken.YESTERDAY:
				spec.day--;
				token = scanner.nextToken();
				break;
			case TimeToken.TODAY:	/* force ourselves to stay in today - no further processing */
				token = scanner.nextToken();
				break;
			case TimeToken.TOMORROW:
				spec.day++;
				token = scanner.nextToken();
				break;
			case TimeToken.JAN:
			case TimeToken.FEB:
			case TimeToken.MAR:
			case TimeToken.APR:
			case TimeToken.MAY:
			case TimeToken.JUN:
			case TimeToken.JUL:
			case TimeToken.AUG:
			case TimeToken.SEP:
			case TimeToken.OCT:
			case TimeToken.NOV:
			case TimeToken.DEC:
				/* do month mday [year] */
				mon = (token.id - TimeToken.JAN);
				expectToken(TimeToken.NUMBER, "the day of the month should follow month name");
				mday = Long.parseLong(token.value);
				token = scanner.nextToken();
				if (token.id == TimeToken.NUMBER) {
					year = Long.parseLong(token.value);
					token = scanner.nextToken();
				}
				else {
					year = spec.year;
				}
				assignDate(mday, mon, year);
				break;
			case TimeToken.SUN:
			case TimeToken.MON:
			case TimeToken.TUE:
			case TimeToken.WED:
			case TimeToken.THU:
			case TimeToken.FRI:
			case TimeToken.SAT:
				/* do a particular day of the week */
				wday = (token.id - TimeToken.SUN);
				spec.day += (wday - spec.wday);
				token = scanner.nextToken();
				break;
			case TimeToken.NUMBER:
				/* get numeric <sec since 1970>, MM/DD/[YY]YY, or DD.MM.[YY]YY */
				// int tlen = token.value.length();
				mon = Long.parseLong(token.value);
				if (mon > 10L * 365L * 24L * 60L * 60L) {
					spec.localtime(mon);
					token = scanner.nextToken();
					break;
				}
				if (mon > 19700101 && mon < 24000101) { /*works between 1900 and 2400 */
					year = mon / 10000;
					mday = mon % 100;
					mon = (mon / 100) % 100;
					token = scanner.nextToken();
				}
				else {
					token = scanner.nextToken();
					if (mon <= 31 && (token.id == TimeToken.SLASH || token.id == TimeToken.DOT)) {
						int sep = token.id;
						expectToken(TimeToken.NUMBER, "there should be " +
								(sep == TimeToken.DOT ? "month" : "day") +
								" number after " +
								(sep == TimeToken.DOT ? '.' : '/'));
						mday = Long.parseLong(token.value);
						token = scanner.nextToken();
						if (token.id == sep) {
							expectToken(TimeToken.NUMBER, "there should be year number after " +
									(sep == TimeToken.DOT ? '.' : '/'));
							year = Long.parseLong(token.value);
							token = scanner.nextToken();
						}
						/* flip months and days for European timing */
						if (sep == TimeToken.DOT) {
							long x = mday;
							mday = mon;
							mon = x;
						}
					}
				}
				mon--;
				if (mon < 0 || mon > 11) {
					throw new RrdException("Did you really mean month " + (mon + 1));
				}
				if (mday < 1 || mday > 31) {
					throw new RrdException("I'm afraid that " + mday +
							" is not a valid day of the month");
				}
				assignDate(mday, mon, year);
				break;
		}
	}

	/**
	 * Parses the input string specified in the constructor.
	 *
	 * @return Object representing parsed date/time.
	 * @throws RrdException Thrown if the date string cannot be parsed.
	 */
	public TimeSpec parse() throws RrdException {
		long now = Util.getTime();
		int hr = 0;
		/* this MUST be initialized to zero for midnight/noon/teatime */
		/* establish the default time reference */
		spec.localtime(now);
		token = scanner.nextToken();
		switch (token.id) {
			case TimeToken.PLUS:
			case TimeToken.MINUS:
				break; /* jump to OFFSET-SPEC part */
			case TimeToken.START:
				spec.type = TimeSpec.TYPE_START;
				/* FALLTHRU */
			case TimeToken.END:
				if (spec.type != TimeSpec.TYPE_START) {
					spec.type = TimeSpec.TYPE_END;
				}
				/* FALLTHRU */
                        case TimeToken.EPOCH:
                                /* FALLTHRU */
			case TimeToken.NOW:
				int time_reference = token.id;
                                if (token.id != TimeToken.NOW) {
                                    spec.year = spec.month = spec.day = spec.hour = spec.min = spec.sec = 0;
                                }
				token = scanner.nextToken();
				if (token.id == TimeToken.PLUS || token.id == TimeToken.MINUS) {
					break;
				}
				if (time_reference == TimeToken.START || time_reference == TimeToken.END) {
					throw new RrdException("Words 'start' or 'end' MUST be followed by +|- offset");
				}
				else if (token.id != TimeToken.EOF) {
					throw new RrdException("If 'now' or 'epoch' is followed by a token it must be +|- offset");
				}
				break;
				/* Only absolute time specifications below */
			case TimeToken.NUMBER:
				timeOfDay();
				//Keep going; there might be a date after the time of day, which day() will pick up
				/* fix month parsing */
			case TimeToken.JAN:
			case TimeToken.FEB:
			case TimeToken.MAR:
			case TimeToken.APR:
			case TimeToken.MAY:
			case TimeToken.JUN:
			case TimeToken.JUL:
			case TimeToken.AUG:
			case TimeToken.SEP:
			case TimeToken.OCT:
			case TimeToken.NOV:
			case TimeToken.DEC:
			case TimeToken.TODAY:
			case TimeToken.YESTERDAY:
			case TimeToken.TOMORROW:
				day();
				if (token.id != TimeToken.NUMBER) {
					break;
				}
				//Allows (but does not require) the time to be specified after the day.  This extends the rrdfetch specifiation
				timeOfDay();
				break;

				/* evil coding for TEATIME|NOON|MIDNIGHT - we've initialized
				 * hr to zero up above, then fall into this case in such a
				 * way so we add +12 +4 hours to it for teatime, +12 hours
				 * to it for noon, and nothing at all for midnight, then
				 * set our rettime to that hour before leaping into the
				 * month scanner
				 */
			case TimeToken.TEATIME:
				hr += 4;
				/* FALLTHRU */
			case TimeToken.NOON:
				hr += 12;
				/* FALLTHRU */
			case TimeToken.MIDNIGHT:
				spec.hour = hr;
				spec.min = 0;
				spec.sec = 0;
				token = scanner.nextToken();
				day();
				break;
			default:
				throw new RrdException("Unparsable time: " + token.value);
		}

		/*
		 * the OFFSET-SPEC part
		 *
		 * (NOTE, the sc_tokid was prefetched for us by the previous code)
		 */
		if (token.id == TimeToken.PLUS || token.id == TimeToken.MINUS) {
			scanner.setContext(false);
			while (token.id == TimeToken.PLUS || token.id == TimeToken.MINUS ||
					token.id == TimeToken.NUMBER) {
				if (token.id == TimeToken.NUMBER) {
					plusMinus(PREVIOUS_OP);
				}
				else {
					plusMinus(token.id);
				}
				token = scanner.nextToken();
				/* We will get EOF eventually but that's OK, since
				token() will return us as many EOFs as needed */
			}
		}
		/* now we should be at EOF */
		if (token.id != TimeToken.EOF) {
			throw new RrdException("Unparsable trailing text: " + token.value);
		}
		return spec;
	}
}