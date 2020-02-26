package org.rrd4j.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import org.rrd4j.core.Util;

import com.tomgibara.crinch.hashing.PerfectStringHash;

class RpnCalculator {
    private enum Token_Symbol {
        TKN_VAR("") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.token.values[s.slot]);
                s.token_rpi = s.rpi;
            }
        },
        TKN_NUM("") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.token.number);
            }
        },

        // Arithmetics
        TKN_PLUS("+") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.pop() + c.pop());
            }
        },
        TKN_ADDNAN("ADDNAN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : x1 + x2));
            }
        },
        TKN_MINUS("-") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 - x2);
            }
        },
        TKN_MULT("*") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.pop() * c.pop());
            }
        },
        TKN_DIV("/") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 / x2);
            }
        },
        TKN_MOD("%") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 % x2);
            }
        },

        TKN_SIN("SIN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.sin(c.pop()));
            }
        },
        TKN_COS("COS") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.cos(c.pop()));
            }
        },
        TKN_LOG("LOG") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.log(c.pop()));
            }
        },
        TKN_EXP("EXP") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.exp(c.pop()));
            }
        },
        TKN_SQRT("SQRT") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.sqrt(c.pop()));
            }
        },
        TKN_ATAN("ATAN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.atan(c.pop()));
            }
        },
        TKN_ATAN2("ATAN2") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(Math.atan2(x1, x2));
            }
        },

        TKN_FLOOR("FLOOR") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.floor(c.pop()));
            }
        },
        TKN_CEIL("CEIL") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.ceil(c.pop()));
            }
        },

        TKN_DEG2RAD("DEG2RAD") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.toRadians(c.pop()));
            }
        },
        TKN_RAD2DEG("RAD2DEG") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.toDegrees(c.pop()));
            }
        },
        TKN_ROUND("ROUND") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.round(c.pop()));
            }
        },
        TKN_POW("POW") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(Math.pow(x1, x2));
            }
        },
        TKN_ABS("ABS") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.abs(c.pop()));
            }
        },
        TKN_RANDOM("RANDOM") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.random());
            }
        },
        TKN_RND("RND") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.floor(c.pop() * Math.random()));
            }
        },

        // Boolean operators
        TKN_UN("UN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.isNaN(c.pop()) ? 1 : 0);
            }
        },
        TKN_ISINF("ISINF") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.isInfinite(c.pop()) ? 1 : 0);
            }            
        },
        TKN_LT("LT") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 < x2 ? 1 : 0);
            }
        },
        TKN_LE("LE") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 <= x2 ? 1 : 0);
            }
        },
        TKN_GT("GT") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 > x2 ? 1 : 0);
            }
        },
        TKN_GE("GE") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 >= x2 ? 1 : 0);
            }
        },
        TKN_EQ("EQ") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 == x2 ? 1 : 0);
            }
        },
        TKN_NE("NE") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 != x2 ? 1 : 0);
            }
        },
        TKN_IF("IF") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x3 = c.pop();
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 != 0 ? x2 : x3);
            }
        },

        // Comparing values
        TKN_MIN("MIN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.min(c.pop(), c.pop()));
            }
        },
        TKN_MAX("MAX") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.max(c.pop(), c.pop()));
            }
        },
        TKN_MINNAN("MINNAN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : Math.min(x1, x2)));
            }
        },
        TKN_MAXNAN("MAXNAN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                double x2 = c.pop();
                c.push(Double.isNaN(x1) ? x2 : (Double.isNaN(x2) ? x1 : Math.max(x1, x2)));
            }
        },
        TKN_LIMIT("LIMIT") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x3 = c.pop();
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x1 < x2 || x1 > x3 ? Double.NaN : x1);
            }
        },

        // Processing the stack directly
        TKN_DUP("DUP") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.peek());
            }
        },
        TKN_EXC("EXC") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(x2);
                c.push(x1);
            }
        },
        TKN_POP("POP") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.pop();
            }
        },

        // Special values
        TKN_UNKN("UNKN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.NaN);
            }
        },
        TKN_PI("PI") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.PI);
            }
        },
        TKN_E("E") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Math.E);
            }
        },
        TKN_INF("INF") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.POSITIVE_INFINITY);
            }
        },
        TKN_NEGINF("NEGINF") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Double.NEGATIVE_INFINITY);
            }
        },

        // Logical operator
        TKN_AND("AND") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push((x1 != 0 && x2 != 0) ? 1 : 0);
            }
        },
        TKN_OR("OR") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push((x1 != 0 || x2 != 0) ? 1 : 0);
            }
        },
        TKN_XOR("XOR") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x2 = c.pop();
                double x1 = c.pop();
                c.push(((x1 != 0 && x2 == 0) || (x1 == 0 && x2 != 0)) ? 1 : 0);
            }
        },

        TKN_PREV("PREV") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push((s.slot == 0) ? Double.NaN : s.token.values[s.slot - 1]);
            }
        },

        //Time and date operator
        TKN_STEP("STEP") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.timeStep);
            }
        },
        TKN_NOW("NOW") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(Util.getTime());
            }
        },
        TKN_TIME("TIME") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.timestamps[s.slot]);
            }
        },
        TKN_LTIME("LTIME") {
            @Override
            void do_method(RpnCalculator c, State s) {
                TimeZone tz = s.getTimeZone();
                c.push((double)(c.timestamps[s.slot] + ((long) tz.getOffset(c.timestamps[s.slot]) / 1000D)));
            }
        },
        TKN_YEAR("YEAR") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.YEAR));
            }
        },
        TKN_MONTH("MONTH") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.MONTH) + 1);
            }
        },
        TKN_DATE("DATE") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.DAY_OF_MONTH));
            }
        },
        TKN_HOUR("HOUR") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.HOUR_OF_DAY));
            }
        },
        TKN_MINUTE("MINUTE") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.MINUTE));
            }
        },
        TKN_SECOND("SECOND") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.SECOND));
            }
        },
        TKN_WEEK("WEEK") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(c.getCalendarField(c.pop(), Calendar.WEEK_OF_YEAR));
            }
        },
        TKN_SIGN("SIGN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                double x1 = c.pop();
                c.push(Double.isNaN(x1) ? Double.NaN : x1 > 0 ? +1 : x1 < 0 ? -1 : 0);
            }
        },
        TKN_SORT("SORT") {
            @Override
            void do_method(RpnCalculator c, State s) {
                int n = (int) c.pop();
                double[] array = new double[n];
                for(int i = 0; i < n; i++) {
                    array[i] = c.pop();
                }
                Arrays.sort(array);
                for (int i = 0; i < n; i++) {
                    c.push(array[i]);
                }
            }
        },
        TKN_REV("REV") {
            @Override
            void do_method(RpnCalculator c, State s) {
                int n = (int) c.pop();
                double[] array = new double[n];
                for(int i = 0; i < n; i++) {
                    array[i] = c.pop();
                }
                for (int i = 0; i < n; i++) {
                    c.push(array[i]);
                }
            }
        },
        TKN_AVG("AVG"){
            @Override
            void do_method(RpnCalculator c, State s) {
                int count = 0;
                int n = (int) c.pop();
                double sum = 0.0;
                while (n > 0) {
                    double x1 = c.pop();
                    n--;

                    if (Double.isNaN(x1)) {
                        continue;
                    }
                    sum += x1;
                    count++;
                }
                if (count > 0) {
                    c.push(sum / count);
                } else {
                    c.push(Double.NaN);
                }
            }
        },
        TKN_COUNT("COUNT") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.push(s.slot + 1.0);
            }
        },
        TKN_TREND("TREND") {
            @Override
            void do_method(RpnCalculator c, State s) {
                int dur = (int) c.pop();
                c.pop();
                /*
                 * OK, so to match the output from rrdtool, we have to go *forward* 2 timeperiods.
                 * So at t[59] we use the average of t[1]..t[61]
                 *
                 */

                if ((s.slot+1) < Math.ceil(dur / c.timeStep)) {
                    c.push(Double.NaN);
                } else {
                    double[] vals = c.dataProcessor.getValues(c.tokens[s.token_rpi].variable);
                    boolean ignorenan = s.token.id == TKN_TRENDNAN;
                    double accum = 0.0;
                    int count = 0;

                    int start = (int) (Math.ceil(dur / c.timeStep));
                    int row = 2;
                    while ((s.slot + row) > vals.length) {
                        row --;
                    }

                    for(; start > 0; start--) {
                        double val = vals[s.slot + row - start];
                        if (ignorenan || !Double.isNaN(val)) {
                            accum = Util.sum(accum, val);
                            ++count;
                        }
                    }
                    c.push((count == 0) ? Double.NaN : (accum / count));
                }
            }
        },
        TKN_TRENDNAN("TRENDNAN") {
            @Override
            void do_method(RpnCalculator c, State s) {
                TKN_TREND.do_method(c, s);
            }
        },
        TKN_PREDICT("PREDICT") {
            @Override
            void do_method(RpnCalculator c, State s) {
                c.pop(); // Clear the value of our variable

                /* the local averaging window (similar to trend, but better here, as we get better statistics thru numbers)*/
                int locstepsize = (int) c.pop();
                /* the number of shifts and range-checking*/
                int num_shifts = (int) c.pop();
                double[] multipliers;

                // handle negative shifts special
                if (num_shifts < 0) {
                    multipliers = new double[1];
                    multipliers[0] = c.pop();
                } else {
                    multipliers = new double[num_shifts];
                    for(int i = 0; i < num_shifts; i++) {
                        multipliers[i] = c.pop();
                    }
                }

                /* the real calculation */
                double val = Double.NaN;

                /* the info on the datasource */
                double[] vals = c.dataProcessor.getValues(c.tokens[s.rpi-1].variable);

                int locstep = (int) Math.ceil((float) locstepsize / (float) c.timeStep);

                /* the sums */
                double sum = 0;
                double sum2 = 0;
                int count = 0;

                /* now loop for each position */
                int doshifts = Math.abs(num_shifts);
                for (int loop = 0; loop < doshifts; loop++) {
                    /* calculate shift step */
                    int shiftstep;
                    if (num_shifts < 0) {
                        shiftstep = loop * (int) multipliers[0];
                    } else {
                        shiftstep = (int) multipliers[loop];
                    }
                    if (shiftstep < 0) {
                        throw new RuntimeException("negative shift step not allowed: " + shiftstep);
                    }
                    shiftstep = (int) Math.ceil((float) shiftstep / (float) c.timeStep);
                    /* loop all local shifts */
                    for (int i = 0; i <= locstep; i++) {
                        int offset = shiftstep + i;
                        if ((offset >= 0) && (offset < s.slot)) {
                            /* get the value */
                            val = vals[s.slot - offset];

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
                if (s.token.id == TKN_PREDICT) {  /* the average */
                    if (count > 0) {
                        val = sum / (double) count;
                    }
                } else {
                    if (count > 1) { /* the sigma case */
                        val = count * sum2 - sum * sum;
                        if (val < 0) {
                            val = Double.NaN;
                        } else {
                            val = Math.sqrt(val / (count * (count - 1.0)));
                        }
                    }
                }
                c.push(val);
            }

        },
        TKN_PREDICTSIGMA("PREDICTSIGMA") {
            @Override
            void do_method(RpnCalculator c, State s) {
                TKN_PREDICT.do_method(c, s);
            }
        };

        public final String token_string;
        Token_Symbol(String token_string) {
            this.token_string = token_string;
        }
        abstract void do_method(RpnCalculator c, State s);
    }

    private static final Token_Symbol[] symbols;
    private static final PerfectStringHash perfect;
    static
    {
        List<String> tokenStrings = new ArrayList<String>(Token_Symbol.values().length);
        for(Token_Symbol s: Token_Symbol.values()) {
            if(! s.token_string.isEmpty()) {
                tokenStrings.add(s.token_string);
            }
        }

        String[] array = tokenStrings.toArray(new String[tokenStrings.size()]);
        perfect = new PerfectStringHash(array);
        symbols = new Token_Symbol[tokenStrings.size()];
        for(Token_Symbol s: Token_Symbol.values()) {
            int hash = perfect.hashAsInt(s.token_string);
            if(hash >= 0) {
                symbols[hash] = s;
            }
        }
    }

    private final String rpnExpression;
    private final String sourceName;
    private final DataProcessor dataProcessor;

    private final Token[] tokens;
    private final RpnStack stack = new RpnStack();
    private final double[] calculatedValues;
    private final long[] timestamps;
    private final double timeStep;
    private final List<String> sourcesNames;

    RpnCalculator(String rpnExpression, String sourceName, DataProcessor dataProcessor) {
        this.rpnExpression = rpnExpression;
        this.sourceName = sourceName;
        this.dataProcessor = dataProcessor;
        this.timestamps = dataProcessor.getTimestamps();
        this.timeStep = (double)(timestamps[1] - timestamps[0]);
        this.calculatedValues = new double[timestamps.length];
        this.sourcesNames = Arrays.asList(dataProcessor.getSourceNames());
        String[] tokensString = rpnExpression.split(" *, *");
        tokens = new Token[tokensString.length];
        for (int i = 0; i < tokensString.length; i++) {
            tokens[i] = createToken(tokensString[i].trim());
        }
    }

    private Token createToken(String parsedText) {
        Token token;
        int hash = perfect.hashAsInt(parsedText);
        if (hash >= 0 ){
            token = new Token(symbols[hash]);
        }
        else if (parsedText.equals("PREV")) {
            token = new Token(Token_Symbol.TKN_PREV, sourceName, calculatedValues);
        }
        else if (parsedText.startsWith("PREV(") && parsedText.endsWith(")")) {
            String variable = parsedText.substring(5, parsedText.length() - 1);
            token = new Token(Token_Symbol.TKN_PREV, variable, dataProcessor.getValues(variable));
        }
        else if (Util.isDouble(parsedText)) {
            token = new Token(Token_Symbol.TKN_NUM, Util.parseDouble(parsedText));
        }
        else if (sourcesNames.contains(parsedText)){
            token = new Token(Token_Symbol.TKN_VAR, parsedText, dataProcessor.getValues(parsedText));
        }
        else {
            throw new IllegalArgumentException("Unexpected RPN token encountered: " +  parsedText);
        }
        return token;
    }

    double[] calculateValues() {
        State s = new State();
        for (int slot = 0; slot < timestamps.length; slot++) {
            resetStack();
            s.rpi = 0;
            s.token_rpi = -1;
            for (Token token: tokens) {
                s.token = token;
                s.slot = slot;
                token.id.do_method(this, s);
                s.rpi++;
            }
            calculatedValues[slot] = pop();
            // check if stack is empty only on the first try
            if (slot == 0 && !isStackEmpty()) {
                throw new IllegalArgumentException("Stack not empty at the end of calculation. " +
                        "Probably bad RPN expression [" + rpnExpression + "]");
            }
        }
        return calculatedValues;
    }

    private double getCalendarField(double timestamp, int field) {
        Calendar calendar = Util.getCalendar((long) (timestamp));
        return calendar.get(field);
    }

    private void push(final double x) {
        stack.push(x);
    }

    private double pop() {
        return stack.pop();
    }

    private double peek() {
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

        void push(double x) {
            if (pos >= MAX_STACK_SIZE) {
                throw new IllegalArgumentException("PUSH failed, RPN stack full [" + MAX_STACK_SIZE + "]");
            }
            stack[pos++] = x;
        }

        double pop() {
            if (pos <= 0) {
                throw new IllegalArgumentException("POP failed, RPN stack is empty");
            }
            return stack[--pos];
        }

        double peek() {
            if (pos <= 0) {
                throw new IllegalArgumentException("PEEK failed, RPN stack is empty");
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

    private final class State {
        private int token_rpi;
        private int rpi;
        Token token;
        int slot;
        TimeZone getTimeZone() {
            return RpnCalculator.this.dataProcessor.getTimeZone();
        }
    }

    private static final class Token {
        final Token_Symbol id;
        final double number;
        final String variable;
        final double[] values;
        Token(Token_Symbol id) {
            this.id = id;
            this.values = null;
            this.variable = "";
            this.number = Double.NaN;
        }
        Token(Token_Symbol id, String variable, double[] values) {
            this.id = id;
            this.variable = variable;
            this.values = values;
            this.number = Double.NaN;
        }
        Token(Token_Symbol id, double number) {
            this.id = id;
            this.values = null;
            this.variable = "";
            this.number = number;
        }
    }
}
