package org.rrd4j.data;

class CDef extends Source implements NonRrdSource {
    private final String rpnExpression;

    CDef(String name, String rpnExpression) {
        super(name);
        this.rpnExpression = rpnExpression;
    }

    String getRpnExpression() {
        return rpnExpression;
    }
    
    /** {@inheritDoc} */
    public void calculate(long tStart, long tEnd, DataProcessor dataProcessor) {
        RpnCalculator calc = new RpnCalculator(rpnExpression, getName(), dataProcessor);
        setValues(calc.calculateValues());
    }
}
