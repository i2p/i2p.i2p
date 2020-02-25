package org.rrd4j;

import org.rrd4j.data.Variable;

/**
 * Enumeration of available consolidation functions. Note that data aggregation inevitably leads to
 * loss of precision and information. The trick is to pick the aggregate function such that the interesting
 * properties of your data are kept across the aggregation process.
 */
public enum ConsolFun {
    /**
     * The average of the data points is stored.
     */
    AVERAGE {
        @Override
        public Variable getVariable() {
            return new Variable.AVERAGE();
        }
    },

    /**
     * The smallest of the data points is stored.
     */
    MIN {
        @Override
        public Variable getVariable() {
            return new Variable.MIN();
        }
    },

    /**
     * The largest of the data points is stored.
     */
    MAX {
        @Override
        public Variable getVariable() {
            return new Variable.MAX();
        }
    },

    /**
     * The last data point is used.
     */
    LAST {
        @Override
        public Variable getVariable() {
            return new Variable.LAST();
        }
    },

    /**
     * The fist data point is used.
     */
    FIRST {
        @Override
        public Variable getVariable() {
            return new Variable.FIRST();
        }
    },

    /**
     * The total of the data points is stored.
     */
    TOTAL {
        @Override
        public Variable getVariable() {
            return new Variable.TOTAL();
        }
    };

    public abstract Variable getVariable();
}
