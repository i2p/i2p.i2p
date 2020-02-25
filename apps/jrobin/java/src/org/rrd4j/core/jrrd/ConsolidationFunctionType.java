package org.rrd4j.core.jrrd;

import org.rrd4j.ConsolFun;

/**
 * Class ConsolidationFunctionType
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public enum ConsolidationFunctionType {
    AVERAGE {
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.AVERAGE;
        }
    },
    MIN {
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MIN;
        }
    },
    MAX {
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.MAX;
        }
    },
    LAST {
        @Override
        public ConsolFun getConsolFun() {
            return ConsolFun.LAST;
        }
    },
    HWPREDICT {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("HWPREDICT not supported");
        }
    },
    SEASONAL {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("SEASONAL not supported");
        }
    },
    DEVPREDICT {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVPREDICT not supported");
        }
    },
    DEVSEASONAL {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("DEVSEASONAL not supported");
        }
    },
    FAILURES {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("FAILURES not supported");
        }
    },
    MHWPREDICT {
        @Override
        public ConsolFun getConsolFun() {
            throw new UnsupportedOperationException("MHWPREDICT not supported");
        }
    };

    public abstract ConsolFun getConsolFun();
}
