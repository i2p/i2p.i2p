/**
 * RRD4J is a high performance data logging and graphing system for time series data, implementing
 * RRDTool's functionality in Java. It follows much of the same logic and uses the same data sources,
 * archive types and definitions as RRDTool does.
 * <p>
 * RRD4J supports all standard operations on Round Robin Database (RRD) files: CREATE, UPDATE, FETCH,
 * LAST, DUMP, XPORT and GRAPH. RRD4J's API is made for those who are familiar with RRDTool's concepts
 * and logic, but prefer to work with pure java. If you provide the same data to RRDTool and RRD4J,
 * you will get very similar results and graphs.
 * <p>
 * RRD4J does not use native functions and libraries, has no Runtime.exec() calls and does not require
 * RRDTool to be present. RRD4J is distributed as a software library (jar files) and comes with full
 * java source code (Apache License 2.0).
 * <p>
 * You will not understand a single thing here if you are not already familiar with RRDTool. Basic
 * concepts and terms (such as: datasource, archive, datasource type, consolidation functions, archive steps/rows,
 * heartbeat, RRD step, RPN, graph DEFs and CDEFs) are not explained here because they have exactly the same
 * meaning in RRD4J and RRDTool. If you are a novice RRDTool/RRD4J user,
 * <a href="http://oss.oetiker.ch/rrdtool/tut/rrdtutorial.en.html">this annotated RRDTool tutorial</a> is a
 * good place to start. A, <a href="https://github.com/rrd4j/rrd4j/wiki/Tutorial">adapted version</a> for RRD4J is available
 */
package org.rrd4j;
