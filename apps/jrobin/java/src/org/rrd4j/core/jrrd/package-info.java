/**
 * <p>This package provides read-only access to natives RRD file.</p>
 * 
 * Currently this can read RRD files that were generated big or little endian machines, 32 or 64 bits word, and 4 or 8 bytes alignment.
 * So it's know to work on a least
 * <ul>
 * <li> x86 Linux
 * <li> x86_64 Linux
 * <li> x86_64 Solaris
 * <li> sparc v8 (32 bits) Solaris
 * <li> sparc v9 (64 bits) Solaris
 * </ul>
 * <p>But it should work on other environments too.</p>
 * <p>Typical usage:</p>
 * <pre>
 * RRDatabase db = new RRDatabase("native.rrd");
 * RrdGraphDef() gd = RrdGraphDef();
 * Calendar endCal = Calendar.getInstance();
 * endCal.set(Calendar.MILLISECOND, 0);
 * Calendar startCal = (Calendar) endCal.clone();
 * startCal.add(Calendar.DATE, -1);
 * DataChunk chunk = db.getData(ConsolidationFunctionType.AVERAGE, startCal.getTime(), endCal.getTime(), 1L);
 * for(String name: db.getDataSourcesName()) {
 *     gd.datasource(name, chunk.toPlottable(name));
 * }
 * </pre>
 *
 * @author <a href="mailto:ciaran@codeloop.com">Ciaran Treanor</a>
 * @version $Revision: 1.1 $
 */
package org.rrd4j.core.jrrd;
