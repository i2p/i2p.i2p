package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.util.Properties;
	
/**
 *  The values in the SessionsDB
 *
 *  @since 0.9.25 moved from SAMv3Handler
 */
class SessionRecord {
	private final String m_dest ;
	private final Properties m_props ;
	private ThreadGroup m_threadgroup ;
	private final SAMv3Handler m_handler ;

	public SessionRecord( String dest, Properties props, SAMv3Handler handler )
	{
		m_dest = dest; 
		m_props = new Properties() ;
		m_props.putAll(props);
		m_handler = handler ;
	}

	public SessionRecord( SessionRecord in )
	{
		m_dest = in.getDest();
		m_props = in.getProps();
		m_threadgroup = in.getThreadGroup();
		m_handler = in.getHandler();
	}

	public String getDest()
	{
		return m_dest;
	}

	/**
	 * Warning - returns a copy.
	 * @return a copy
	 */
	synchronized public Properties getProps()
	{
		Properties p = new Properties();
		p.putAll(m_props);
		return m_props;
	}

	public SAMv3Handler getHandler()
	{
		return m_handler ;
	}

	synchronized public ThreadGroup getThreadGroup()
	{
		return m_threadgroup ;
	}

	synchronized public void createThreadGroup(String name)
	{
		if (m_threadgroup == null)
			m_threadgroup = new ThreadGroup(name);
	}
}
