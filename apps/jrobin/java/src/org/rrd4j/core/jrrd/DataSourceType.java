package org.rrd4j.core.jrrd;

import org.rrd4j.DsType;

/**
 * Class DataSourceType
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
public enum DataSourceType {
    COUNTER {
        @Override
        public DsType getDsType() {
            return DsType.COUNTER;
        }
    },
    ABSOLUTE {
        @Override
        public DsType getDsType() {
            return DsType.ABSOLUTE;
        }
    },
    GAUGE {
        @Override
        public DsType getDsType() {
            return DsType.GAUGE;
        }
    },
    DERIVE {
        @Override
        public DsType getDsType() {
            return DsType.DERIVE;
        }
    },
    CDEF {
        @Override
        public DsType getDsType() {
            throw new UnsupportedOperationException("CDEF not supported");
        }
    };

    public abstract DsType getDsType();
}
