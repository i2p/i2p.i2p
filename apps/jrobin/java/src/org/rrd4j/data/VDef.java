package org.rrd4j.data;

import java.util.Arrays;

class VDef extends Source implements NonRrdSource  {
    private final String defName;
    private final Variable var;

    VDef(String name, String defName, Variable aggr) {
        super(name);
        this.defName = defName;
        this.var = aggr;
    }

    String getDefName() {
        return defName;
    }

    /** {@inheritDoc} */
    public void calculate(long tStart, long tEnd, DataProcessor dataProcessor) {
        String defName = getDefName();
        Source source = dataProcessor.getSource(defName);
        var.calculate(source, tStart, tEnd);
    }

    public Variable.Value getValue() {
        return var.getValue();
    }

    /* (non-Javadoc)
     * @see org.rrd4j.data.Source#getValues()
     */
    @Override
    double[] getValues() {
        int count = getTimestamps().length;
        double[] values = new double[count];
        Arrays.fill(values, var.getValue().value);
        return values;
    }

}
