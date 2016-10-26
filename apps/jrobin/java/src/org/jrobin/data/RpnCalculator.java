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

package org.jrobin.data;

import java.util.Arrays;
import org.jrobin.core.RrdException;
import org.jrobin.core.Util;

import java.util.Calendar;
import java.util.StringTokenizer;
import java.util.TimeZone;

class RpnCalculator {
	private static final byte TKN_VAR = 0;
	private static final byte TKN_NUM = 1;
	private static final byte TKN_PLUS = 2;
	private static final byte TKN_MINUS = 3;
	private static final byte TKN_MULT = 4;
	private static final byte TKN_DIV = 5;
	private static final byte TKN_MOD = 6;
	private static final byte TKN_SIN = 7;
	private static final byte TKN_COS = 8;
	private static final byte TKN_LOG = 9;
	private static final byte TKN_EXP = 10;
	private static final byte TKN_FLOOR = 11;
	private static final byte TKN_CEIL = 12;
	private static final byte TKN_ROUND = 13;
	private static final byte TKN_POW = 14;
	private static final byte TKN_ABS = 15;
	private static final byte TKN_SQRT = 16;
	private static final byte TKN_RANDOM = 17;
	private static final byte TKN_LT = 18;
	private static final byte TKN_LE = 19;
	private static final byte TKN_GT = 20;
	private static final byte TKN_GE = 21;
	private static final byte TKN_EQ = 22;
	private static final byte TKN_IF = 23;
	private static final byte TKN_MIN = 24;
	private static final byte TKN_MAX = 25;
	private static final byte TKN_LIMIT = 26;
	private static final byte TKN_DUP = 27;
	private static final byte TKN_EXC = 28;
	private static final byte TKN_POP = 29;
	private static final byte TKN_UN = 30;
	private static final byte TKN_UNKN = 31;
	private static final byte TKN_NOW = 32;
	private static final byte TKN_TIME = 33;
	private static final byte TKN_PI = 34;
	private static final byte TKN_E = 35;
	private static final byte TKN_AND = 36;
	private static final byte TKN_OR = 37;
	private static final byte TKN_XOR = 38;
	private static final byte TKN_PREV = 39;
	private static final byte TKN_INF = 40;
	private static final byte TKN_NEGINF = 41;
	private static final byte TKN_STEP = 42;
	private static final byte TKN_YEAR = 43;
	private static final byte TKN_MONTH = 44;
	private static final byte TKN_DATE = 45;
	private static final byte TKN_HOUR = 46;
	private static final byte TKN_MINUTE = 47;
	private static final byte TKN_SECOND = 48;
	private static final byte TKN_WEEK = 49;
	private static final byte TKN_SIGN = 50;
	private static final byte TKN_RND = 51;
        private static final byte TKN_ADDNAN = 52;
        private static final byte TKN_NE = 53;
        private static final byte TKN_ISINF = 54;
        private static final byte TKN_ATAN = 55;
        private static final byte TKN_ATAN2 = 56;
        private static final byte TKN_DEG2RAD = 57;
        private static final byte TKN_RAD2DEG = 58;
        private static final byte TKN_COUNT = 59;
        private static final byte TKN_SORT = 60;
        private static final byte TKN_REV = 61;
        private static final byte TKN_AVG = 62;
        private static final byte TKN_LTIME = 63;
        private static final byte TKN_TREND = 64;
        private static final byte TKN_TRENDNAN = 65;
        private static final byte TKN_PREDICT = 66;
        private static final byte TKN_PREDICTSIGMA = 67;

	private String rpnExpression;
	private String sourceName;
	private DataProcessor dataProcessor;

	private Token[] tokens;
	private RpnStack stack = new RpnStack();
	private double[] calculatedValues;
	private long[] timestamps;
	private double timeStep;

	RpnCalculator(String rpnExpression, String sourceName, DataProcessor dataProcessor) throws RrdException {
		this.rpnExpression = rpnExpression;
		this.sourceName = sourceName;
		this.dataProcessor = dataProcessor;
		this.timestamps = dataProcessor.getTimestamps();
		this.timeStep = this.timestamps[1] - this.timestamps[0];
		this.calculatedValues = new double[this.timestamps.length];
		StringTokenizer st = new StringTokenizer(rpnExpression, ", ");
		tokens = new Token[st.countTokens()];
		for (int i = 0; st.hasMoreTokens(); i++) {
			tokens[i] = createToken(st.nextToken());
		}
	}

	private Token createToken(String parsedText) throws RrdException {
		Token token = new Token();
		if (Util.isDouble(parsedText)) {
			token.id = TKN_NUM;
			token.number = Util.parseDouble(parsedText);
		}
		else if (parsedText.equals("+")) {
			token.id = TKN_PLUS;
		}
		else if (parsedText.equals("-")) {
			token.id = TKN_MINUS;
		}
		else if (parsedText.equals("*")) {
			token.id = TKN_MULT;
		}
		else if (parsedText.equals("/")) {
			token.id = TKN_DIV;
		}
		else if (parsedText.equals("%")) {
			token.id = TKN_MOD;
		}
		else if (parsedText.equals("SIN")) {
			token.id = TKN_SIN;
		}
		else if (parsedText.equals("COS")) {
			token.id = TKN_COS;
		}
		else if (parsedText.equals("LOG")) {
			token.id = TKN_LOG;
		}
		else if (parsedText.equals("EXP")) {
			token.id = TKN_EXP;
		}
		else if (parsedText.equals("FLOOR")) {
			token.id = TKN_FLOOR;
		}
		else if (parsedText.equals("CEIL")) {
			token.id = TKN_CEIL;
		}
		else if (parsedText.equals("ROUND")) {
			token.id = TKN_ROUND;
		}
		else if (parsedText.equals("POW")) {
			token.id = TKN_POW;
		}
		else if (parsedText.equals("ABS")) {
			token.id = TKN_ABS;
		}
		else if (parsedText.equals("SQRT")) {
			token.id = TKN_SQRT;
		}
		else if (parsedText.equals("RANDOM")) {
			token.id = TKN_RANDOM;
		}
		else if (parsedText.equals("LT")) {
			token.id = TKN_LT;
		}
		else if (parsedText.equals("LE")) {
			token.id = TKN_LE;
		}
		else if (parsedText.equals("GT")) {
			token.id = TKN_GT;
		}
		else if (parsedText.equals("GE")) {
			token.id = TKN_GE;
		}
		else if (parsedText.equals("EQ")) {
			token.id = TKN_EQ;
		}
		else if (parsedText.equals("IF")) {
			token.id = TKN_IF;
		}
		else if (parsedText.equals("MIN")) {
			token.id = TKN_MIN;
		}
		else if (parsedText.equals("MAX")) {
			token.id = TKN_MAX;
		}
		else if (parsedText.equals("LIMIT")) {
			token.id = TKN_LIMIT;
		}
		else if (parsedText.equals("DUP")) {
			token.id = TKN_DUP;
		}
		else if (parsedText.equals("EXC")) {
			token.id = TKN_EXC;
		}
		else if (parsedText.equals("POP")) {
			token.id = TKN_POP;
		}
		else if (parsedText.equals("UN")) {
			token.id = TKN_UN;
		}
		else if (parsedText.equals("UNKN")) {
			token.id = TKN_UNKN;
		}
		else if (parsedText.equals("NOW")) {
			token.id = TKN_NOW;
		}
		else if (parsedText.equals("TIME")) {
			token.id = TKN_TIME;
		}
		else if (parsedText.equals("LTIME")) {
			token.id = TKN_LTIME;
		}
		else if (parsedText.equals("PI")) {
			token.id = TKN_PI;
		}
		else if (parsedText.equals("E")) {
			token.id = TKN_E;
		}
		else if (parsedText.equals("AND")) {
			token.id = TKN_AND;
		}
		else if (parsedText.equals("OR")) {
			token.id = TKN_OR;
		}
		else if (parsedText.equals("XOR")) {
			token.id = TKN_XOR;
		}
		else if (parsedText.equals("PREV")) {
			token.id = TKN_PREV;
			token.variable = sourceName;
			token.values = calculatedValues;
		}
		else if (parsedText.startsWith("PREV(") && parsedText.endsWith(")")) {
			token.id = TKN_PREV;
			token.variable = parsedText.substring(5, parsedText.length() - 1);
			token.values = dataProcessor.getValues(token.variable);
		}
		else if (parsedText.equals("INF")) {
			token.id = TKN_INF;
		}
		else if (parsedText.equals("NEGINF")) {
			token.id = TKN_NEGINF;
		}
		else if (parsedText.equals("STEP")) {
			token.id = TKN_STEP;
		}
		else if (parsedText.equals("YEAR")) {
			token.id = TKN_YEAR;
		}
		else if (parsedText.equals("MONTH")) {
			token.id = TKN_MONTH;
		}
		else if (parsedText.equals("DATE")) {
			token.id = TKN_DATE;
		}
		else if (parsedText.equals("HOUR")) {
			token.id = TKN_HOUR;
		}
		else if (parsedText.equals("MINUTE")) {
			token.id = TKN_MINUTE;
		}
		else if (parsedText.equals("SECOND")) {
			token.id = TKN_SECOND;
		}
		else if (parsedText.equals("WEEK")) {
			token.id = TKN_WEEK;
		}
		else if (parsedText.equals("SIGN")) {
			token.id = TKN_SIGN;
		}
		else if (parsedText.equals("RND")) {
			token.id = TKN_RND;
		}
		else if (parsedText.equals("ADDNAN")) {
			token.id = TKN_ADDNAN;
		}
		else if (parsedText.equals("NE")) {
			token.id = TKN_NE;
		}
		else if (parsedText.equals("ISINF")) {
			token.id = TKN_ISINF;
		}
		else if (parsedText.equals("ATAN")) {
			token.id = TKN_ATAN;
		}
		else if (parsedText.equals("ATAN2")) {
			token.id = TKN_ATAN2;
		}
		else if (parsedText.equals("DEG2RAD")) {
			token.id = TKN_DEG2RAD;
		}
		else if (parsedText.equals("RAD2DEG")) {
			token.id = TKN_RAD2DEG;
		}
		else if (parsedText.equals("COUNT")) {
			token.id = TKN_COUNT;
		}
		else if (parsedText.equals("SORT")) {
			token.id = TKN_SORT;
		}
		else if (parsedText.equals("REV")) {
			token.id = TKN_REV;
		}
		else if (parsedText.equals("AVG")) {
			token.id = TKN_AVG;
		}
		else if (parsedText.equals("TREND")) {
			token.id = TKN_TREND;
		}
		else if (parsedText.equals("TRENDNAN")) {
			token.id = TKN_TRENDNAN;
		}
		else if (parsedText.equals("PREDICT")) {
			token.id = TKN_PREDICT;
		}
		else if (parsedText.equals("PREDICTSIGMA")) {
			token.id = TKN_PREDICTSIGMA;
		}
		else {
			token.id = TKN_VAR;
			token.variable = parsedText;
			token.values = dataProcessor.getValues(token.variable);
		}
		return token;
	}

	double[] calculateValues() throws RrdException {
                TimeZone tz = TimeZone.getDefault();
		for (int slot = 0; slot < timestamps.length; slot++) {
			resetStack();
                        int token_rpi = -1;
                        for (int rpi = 0; rpi < tokens.length; rpi++) {
                                Token token = tokens[rpi];
				double x1, x2, x3;
				switch (token.id) {
					case TKN_NUM:
						push(token.number);
						break;
					case TKN_VAR:
						push(token.values[slot]);
                                                token_rpi = rpi;
						break;
					case TKN_COUNT:
						push(slot+1);
						break;
					case TKN_PLUS:
						push(pop() + pop());
						break;
					case TKN_MINUS:
						x2 = pop();
						x1 = pop();
						push(x1 - x2);
						break;
					case TKN_MULT:
						push(pop() * pop());
						break;
					case TKN_DIV:
						x2 = pop();
						x1 = pop();
						push(x1 / x2);
						break;
					case TKN_MOD:
						x2 = pop();
						x1 = pop();
						push(x1 % x2);
						break;
					case TKN_SIN:
						push(Math.sin(pop()));
						break;
					case TKN_COS:
						push(Math.cos(pop()));
						break;
					case TKN_ATAN:
						push(Math.atan(pop()));
						break;
					case TKN_ATAN2:
						x2 = pop();
						x1 = pop();
						push(Math.atan2(x1, x2));
						break;
					case TKN_LOG:
						push(Math.log(pop()));
						break;
					case TKN_EXP:
						push(Math.exp(pop()));
						break;
					case TKN_FLOOR:
						push(Math.floor(pop()));
						break;
					case TKN_CEIL:
						push(Math.ceil(pop()));
						break;
					case TKN_ROUND:
						push(Math.round(pop()));
						break;
					case TKN_POW:
						x2 = pop();
						x1 = pop();
						push(Math.pow(x1, x2));
						break;
					case TKN_ABS:
						push(Math.abs(pop()));
						break;
					case TKN_SQRT:
						push(Math.sqrt(pop()));
						break;
					case TKN_RANDOM:
						push(Math.random());
						break;
					case TKN_LT:
						x2 = pop();
						x1 = pop();
						push(x1 < x2 ? 1 : 0);
						break;
					case TKN_LE:
						x2 = pop();
						x1 = pop();
						push(x1 <= x2 ? 1 : 0);
						break;
					case TKN_GT:
						x2 = pop();
						x1 = pop();
						push(x1 > x2 ? 1 : 0);
						break;
					case TKN_GE:
						x2 = pop();
						x1 = pop();
						push(x1 >= x2 ? 1 : 0);
						break;
					case TKN_EQ:
						x2 = pop();
						x1 = pop();
						push(x1 == x2 ? 1 : 0);
						break;
					case TKN_NE:
						x2 = pop();
						x1 = pop();
						push(x1 != x2 ? 1 : 0);
						break;
					case TKN_IF:
						x3 = pop();
						x2 = pop();
						x1 = pop();
						push(x1 != 0 ? x2 : x3);
						break;
					case TKN_MIN:
						push(Math.min(pop(), pop()));
						break;
					case TKN_MAX:
						push(Math.max(pop(), pop()));
						break;
					case TKN_LIMIT:
						x3 = pop();
						x2 = pop();
						x1 = pop();
						push(x1 < x2 || x1 > x3 ? Double.NaN : x1);
						break;
					case TKN_DUP:
						push(peek());
						break;
					case TKN_EXC:
						x2 = pop();
						x1 = pop();
						push(x2);
						push(x1);
						break;
					case TKN_POP:
						pop();
						break;
					case TKN_UN:
						push(Double.isNaN(pop()) ? 1 : 0);
						break;
					case TKN_ISINF:
						push(Double.isInfinite(pop()) ? 1 : 0);
						break;
					case TKN_UNKN:
						push(Double.NaN);
						break;
					case TKN_NOW:
						push(Util.getTime());
						break;
					case TKN_TIME:
						push(timestamps[slot]);
						break;
					case TKN_LTIME:
						push(timestamps[slot] + (tz.getOffset(timestamps[slot]) / 1000L));
						break;
					case TKN_PI:
						push(Math.PI);
						break;
					case TKN_E:
						push(Math.E);
						break;
					case TKN_AND:
						x2 = pop();
						x1 = pop();
						push((x1 != 0 && x2 != 0) ? 1 : 0);
						break;
					case TKN_OR:
						x2 = pop();
						x1 = pop();
						push((x1 != 0 || x2 != 0) ? 1 : 0);
						break;
					case TKN_XOR:
						x2 = pop();
						x1 = pop();
						push(((x1 != 0 && x2 == 0) || (x1 == 0 && x2 != 0)) ? 1 : 0);
						break;
					case TKN_PREV:
						push((slot == 0) ? Double.NaN : token.values[slot - 1]);
						break;
					case TKN_INF:
						push(Double.POSITIVE_INFINITY);
						break;
					case TKN_NEGINF:
						push(Double.NEGATIVE_INFINITY);
						break;
					case TKN_STEP:
						push(timeStep);
						break;
					case TKN_YEAR:
						push(getCalendarField(pop(), Calendar.YEAR));
						break;
					case TKN_MONTH:
						push(getCalendarField(pop(), Calendar.MONTH));
						break;
					case TKN_DATE:
						push(getCalendarField(pop(), Calendar.DAY_OF_MONTH));
						break;
					case TKN_HOUR:
						push(getCalendarField(pop(), Calendar.HOUR_OF_DAY));
						break;
					case TKN_MINUTE:
						push(getCalendarField(pop(), Calendar.MINUTE));
						break;
					case TKN_SECOND:
						push(getCalendarField(pop(), Calendar.SECOND));
						break;
					case TKN_WEEK:
						push(getCalendarField(pop(), Calendar.WEEK_OF_YEAR));
						break;
					case TKN_SIGN:
						x1 = pop();
						push(Double.isNaN(x1) ? Double.NaN : x1 > 0 ? +1 : x1 < 0 ? -1 : 0);
						break;
					case TKN_RND:
						push(Math.floor(pop() * Math.random()));
						break;
					case TKN_ADDNAN:
						x2 = pop();
						x1 = pop();
                                                if (Double.isNaN(x1)) {
                                                    push(x2);
                                                } else if (Double.isNaN(x2)) {
                                                    push(x1);
                                                } else {
                                                    push(x1+x2);
                                                }
						break;
					case TKN_DEG2RAD:
						push(Math.toRadians(pop()));
						break;
					case TKN_RAD2DEG:
						push(Math.toDegrees(pop()));
						break;
					case TKN_SORT:
                                        {
                                                int n = (int) pop();
                                                double[] array = new double[n];
                                                for(int i = 0; i < n; i++) {
                                                    array[i] = pop();
                                                }
                                                Arrays.sort(array);
                                                for (int i = 0; i < n; i++) {
                                                    push(array[i]);
                                                }
                                        }
						break;
					case TKN_REV:
                                        {
                                                int n = (int) pop();
                                                double[] array = new double[n];
                                                for(int i = 0; i < n; i++) {
                                                    array[i] = pop();
                                                }
                                                for (int i = 0; i < n; i++) {
                                                    push(array[i]);
                                                }
                                        }
						break;
					case TKN_AVG:
                                        {
                                                int count = 0;
                                                int n = (int) pop();
                                                double sum = 0.0;
                                                while (n > 0) {
                                                    x1 = pop();
                                                    n--;

                                                    if (Double.isNaN(x1)) {
                                                        continue;
                                                    }

                                                    sum += x1;
                                                    count++;
                                                }
                                                if (count > 0) {
                                                    push(sum / count);
                                                } else {
                                                    push(Double.NaN);
                                                }
                                        }
						break;
                                        case TKN_TREND:
                                        case TKN_TRENDNAN:
                                        {
                                                int dur = (int) pop();
                                                pop();
                                                /*
                                                 * OK, so to match the output from rrdtool, we have to go *forward* 2 timeperiods.
                                                 * So at t[59] we use the average of t[1]..t[61]
                                                 *
                                                 */

                                                if ((slot+1) < Math.ceil(dur / timeStep)) {
                                                    push(Double.NaN);
                                                } else {
                                                    double[] vals = dataProcessor.getValues(tokens[token_rpi].variable);
                                                    boolean ignorenan = token.id == TKN_TRENDNAN;
                                                    double accum = 0.0;
                                                    int count = 0;

                                                    int start = (int) (Math.ceil(dur / timeStep));
                                                    int row = 2;
                                                    while ((slot + row) > vals.length) {
                                                        row --;
                                                    }

                                                    for(; start > 0; start--) {
                                                        double val = vals[slot + row - start];
                                                        if (ignorenan || !Double.isNaN(val)) {
                                                            accum = Util.sum(accum, val);
                                                            ++count;
                                                        }
                                                    }
                                                    //System.err.printf("t[%d]: %1.10e / %d\n", slot, (count == 0) ? Double.NaN : (accum / count), count);
                                                    push((count == 0) ? Double.NaN : (accum / count));
                                                }
                                        }
                                                break;
                                        case TKN_PREDICT:
                                        case TKN_PREDICTSIGMA:
                                        {
                                            pop(); // Clear the value of our variable

                                            /* the local averaging window (similar to trend, but better here, as we get better statistics thru numbers)*/
                                            int locstepsize = (int) pop();
                                            /* the number of shifts and range-checking*/
                                            int num_shifts = (int) pop();
                                            double[] multipliers;

                                            // handle negative shifts special
                                            if (num_shifts < 0) {
                                                multipliers = new double[1];
                                                multipliers[0] = pop();
                                            } else {
                                                multipliers = new double[num_shifts];
                                                for(int i = 0; i < num_shifts; i++) {
                                                    multipliers[i] = pop();
                                                }
                                            }

                                            /* the real calculation */
                                            double val = Double.NaN;

                                            /* the info on the datasource */
                                            double[] vals = dataProcessor.getValues(tokens[rpi-1].variable);

                                            int locstep = (int) Math.ceil((float) locstepsize / (float) timeStep);

                                            /* the sums */
                                            double sum = 0;
                                            double sum2 = 0;
                                            int count = 0;

                                            /* now loop for each position */
                                            int doshifts = Math.abs(num_shifts);
                                            for (int loop = 0; loop < doshifts; loop++) {
                                                /* calculate shift step */
                                                int shiftstep = 1;
                                                if (num_shifts < 0) {
                                                    shiftstep = loop * (int) multipliers[0];
                                                } else {
                                                    shiftstep = (int) multipliers[loop];
                                                }
                                                if (shiftstep < 0) {
                                                    throw new RrdException("negative shift step not allowed: " + shiftstep);
                                                }
                                                shiftstep = (int) Math.ceil((float) shiftstep / (float) timeStep);
                                                /* loop all local shifts */
                                                for (int i = 0; i <= locstep; i++) {

                                                    int offset = shiftstep + i;
                                                    if ((offset >= 0) && (offset < slot)) {
                                                        /* get the value */
                                                        val = vals[slot - offset];

                                                        /* and handle the non NAN case only*/
                                                        if (!Double.isNaN(val)) {
                                                            sum = Util.sum(sum, val);
                                                            sum2 = Util.sum(sum2, val * val);
                                                            count++;
                                                        }
                                                    }
                                                }
                                            }
                                            /* do the final calculations */
                                            val = Double.NaN;
                                            if (token.id == TKN_PREDICT) {  /* the average */
                                                if (count > 0) {
                                                    val = sum / (double) count;
                                                }
                                            } else {
                                                if (count > 1) { /* the sigma case */
                                                    val = count * sum2 - sum * sum;
                                                    if (val < 0) {
                                                        val = Double.NaN;
                                                    } else {
                                                        val = Math.sqrt(val / ((float) count * ((float) count - 1.0)));
                                                    }
                                                }
                                            }
                                            push(val);
                                        }
                                                break;
					default:
						throw new RrdException("Unexpected RPN token encountered, token.id=" + token.id);
				}
			}
			calculatedValues[slot] = pop();
			// check if stack is empty only on the first try
			if (slot == 0 && !isStackEmpty()) {
				throw new RrdException("Stack not empty at the end of calculation. " +
						"Probably bad RPN expression [" + rpnExpression + "]");
			}
		}
		return calculatedValues;
	}

	private double getCalendarField(double timestamp, int field) {
		Calendar calendar = Util.getCalendar((long) timestamp);
		return calendar.get(field);
	}

	private void push(double x) throws RrdException {
		stack.push(x);
	}

	private double pop() throws RrdException {
		return stack.pop();
	}

	private double peek() throws RrdException {
		return stack.peek();
	}

	private void resetStack() {
		stack.reset();
	}

	private boolean isStackEmpty() {
		return stack.isEmpty();
	}

	private static final class RpnStack {
		private static final int MAX_STACK_SIZE = 1000;
		private double[] stack = new double[MAX_STACK_SIZE];
		private int pos = 0;

		void push(final double x) throws RrdException {
			if (pos >= MAX_STACK_SIZE) {
				throw new RrdException("PUSH failed, RPN stack full [" + MAX_STACK_SIZE + "]");
			}
			stack[pos++] = x;
		}

		double pop() throws RrdException {
			if (pos <= 0) {
				throw new RrdException("POP failed, RPN stack is empty ");
			}
			return stack[--pos];
		}

		double peek() throws RrdException {
			if (pos <= 0) {
				throw new RrdException("PEEK failed, RPN stack is empty ");
			}
			return stack[pos - 1];
		}

		void reset() {
			pos = 0;
		}

		boolean isEmpty() {
			return pos <= 0;
		}
	}

	private static final class Token {
		byte id = -1;
		double number = Double.NaN;
		String variable = null;
		double[] values = null;
	}
}
