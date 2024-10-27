package org.rrd4j.graph;

import java.util.function.DoubleUnaryOperator;

class LogService {

    static DoubleUnaryOperator resolve(ImageParameters im) {
        boolean sameSign = Math.signum(im.minval) == Math.signum(im.maxval);
        double absMinVal = Math.min(Math.abs(im.minval), Math.abs(im.maxval));
        double absMaxVal = Math.max(Math.abs(im.minval), Math.abs(im.maxval));
        if (! sameSign) {
            return LogService::log10;
        } else if (absMinVal == 0 && absMaxVal < 1) {
            double correction = 1.0 / absMaxVal;
            return v -> log10(v, correction, absMinVal);
        } else if (absMinVal == 0) {
            return LogService::log10;
        } else if (absMinVal < 1) {
            double correction = 1.0 / absMinVal;
            return v -> log10(v, correction, absMinVal);
        } else {
            return LogService::log10;
        }
    }

    private LogService() {

    }

    /**
     * Compute logarithm for the purposes of y-axis.
     */
    private static double log10(double v, double correction, double minval) {
        if (v == minval) {
            return 0.0;
        } else {
            double lv = Math.log10(Math.abs(v) * correction);
            if (lv < 0 || Double.isNaN(lv)) {
                // Don't cross the sign line, round to 0 if that's the case
                return 0.0;
            } else {
                return Math.copySign(lv, v);
            }
        }
    }

    private static double log10(double v) {
        double lv = Math.log10(Math.abs(v));
        if (lv < 0) {
            // Don't cross the sign line, round to 0 if that's the case
            return 0.0;
        } else {
            return Math.copySign(lv, v);
        }
    }

}
