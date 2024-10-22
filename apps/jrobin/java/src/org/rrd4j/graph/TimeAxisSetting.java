package org.rrd4j.graph;

class TimeAxisSetting {
    final long secPerPix;
    final TimeUnit majorUnit;
    final int majorUnitCount;
    final TimeUnit minorUnit;
    final int minorUnitCount;
    final TimeUnit labelUnit;
    final int labelUnitCount;
    final int labelSpan;
    final TimeLabelFormat format;

    TimeAxisSetting(long secPerPix, TimeUnit minorUnit, int minorUnitCount, TimeUnit majorUnit, int majorUnitCount,
            TimeUnit labelUnit, int labelUnitCount, int labelSpan, TimeLabelFormat format) {
        this.secPerPix = secPerPix;
        this.minorUnit = minorUnit;
        this.minorUnitCount = minorUnitCount;
        this.majorUnit = majorUnit;
        this.majorUnitCount = majorUnitCount;
        this.labelUnit = labelUnit;
        this.labelUnitCount = labelUnitCount;
        this.labelSpan = labelSpan;
        this.format = format;
    }

    TimeAxisSetting(long secPerPix, TimeUnit minorUnit, int minorUnitCount, TimeUnit majorUnit, int majorUnitCount,
            TimeUnit labelUnit, int labelUnitCount, int labelSpan) {
        this.secPerPix = secPerPix;
        this.minorUnit = minorUnit;
        this.minorUnitCount = minorUnitCount;
        this.majorUnit = majorUnit;
        this.majorUnitCount = majorUnitCount;
        this.labelUnit = labelUnit;
        this.labelUnitCount = labelUnitCount;
        this.labelSpan = labelSpan;
        this.format = new SimpleTimeLabelFormat(labelUnit.getLabel());
    }

    TimeAxisSetting(TimeAxisSetting s) {
        this.secPerPix = s.secPerPix;
        this.minorUnit = s.minorUnit;
        this.minorUnitCount = s.minorUnitCount;
        this.majorUnit = s.majorUnit;
        this.majorUnitCount = s.majorUnitCount;
        this.labelUnit = s.labelUnit;
        this.labelUnitCount = s.labelUnitCount;
        this.labelSpan = s.labelSpan;
        this.format = s.format;
    }

    TimeAxisSetting(int minorUnit, int minorUnitCount, int majorUnit, int majorUnitCount,
                    int labelUnit, int labelUnitCount, int labelSpan, TimeLabelFormat format) {
        this(0, TimeUnit.resolveUnit(minorUnit), minorUnitCount, TimeUnit.resolveUnit(majorUnit), majorUnitCount,
                TimeUnit.resolveUnit(labelUnit), labelUnitCount, labelSpan, format);
    }

    TimeAxisSetting withLabelFormat(TimeLabelFormat f) {
        return new TimeAxisSetting(
            secPerPix, minorUnit, minorUnitCount, majorUnit, majorUnitCount,
            labelUnit, labelUnitCount, labelSpan, f);
    }
}
