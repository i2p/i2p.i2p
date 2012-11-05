package net.i2p.data;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by str4d in 2012 and released into the public domain 
 * with no warranty of any kind, either expressed or implied.  
 * It probably won't make your computer catch on fire, or eat 
 * your children, but it might.  Use at your own risk.
 *
 */

/**
 * Test harness for loading / storing DateAndFlags objects
 *
 * @author str4d
 */
public class DateAndFlagsTest extends StructureTest {

	@Override
	public DataStructure createDataStructure() throws DataFormatException {
		DateAndFlags daf = new DateAndFlags();
		daf.setDate(0);
		daf.setFlags(0);
		return daf;
	}

	@Override
	public DataStructure createStructureToRead() {
		return new DateAndFlags();
	}

}
