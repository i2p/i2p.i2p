package net.i2p.sam;
/*
 * free (adj.): unencumbered; not under the control of others
 * Written by human in 2004 and released into the public domain
 * with no warranty of any kind, either expressed or implied.
 * It probably won't  make your computer catch on fire, or eat
 * your children, but it might.  Use at your own risk.
 *
 */

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.util.Log;

/**
 * Class able to handle a SAM version 2 client connection.
 *
 * @author mkvore
 */

public class SAMv2Handler extends SAMv1Handler implements SAMRawReceiver, SAMDatagramReceiver, SAMStreamReceiver
{

		private final static Log _log = new Log ( SAMv2Handler.class );


		/**
		 * Create a new SAM version 2 handler.  This constructor expects
		 * that the SAM HELLO message has been still answered (and
		 * stripped) from the socket input stream.
		 *
		 * @param s Socket attached to a SAM client
		 * @param verMajor SAM major version to manage (should be 2)
		 * @param verMinor SAM minor version to manage
		 */
		public SAMv2Handler ( SocketChannel s, int verMajor, int verMinor ) throws SAMException, IOException
		{
			this ( s, verMajor, verMinor, new Properties() );
		}

		/**
		 * Create a new SAM version 2 handler.  This constructor expects
		 * that the SAM HELLO message has been still answered (and
		 * stripped) from the socket input stream.
		 *
		 * @param s Socket attached to a SAM client
		 * @param verMajor SAM major version to manage (should be 2)
		 * @param verMinor SAM minor version to manage
		 * @param i2cpProps properties to configure the I2CP connection (host, port, etc)
		 */

		public SAMv2Handler ( SocketChannel s, int verMajor, int verMinor, Properties i2cpProps ) throws SAMException, IOException
		{
			super ( s, verMajor, verMinor, i2cpProps );
		}
		
		public boolean verifVersion()
		{
			return (verMajor == 2 && verMinor == 0) ;
		}

		SAMStreamSession newSAMStreamSession(String destKeystream, String direction, Properties props )
				throws IOException, DataFormatException, SAMException
		{
			return new SAMv2StreamSession(destKeystream, direction, props, this) ;
		}
		
		
		/* Parse and execute a STREAM message */
		protected boolean execStreamMessage ( String opcode, Properties props )
		{
			if ( getStreamSession() == null )
			{
				_log.error ( "STREAM message received, but no STREAM session exists" );
				return false;
			}

			if ( opcode.equals ( "SEND" ) )
			{
				return execStreamSend ( props );
			}
			else if ( opcode.equals ( "CONNECT" ) )
			{
				return execStreamConnect ( props );
			}
			else if ( opcode.equals ( "CLOSE" ) )
			{
				return execStreamClose ( props );
			}
			else if ( opcode.equals ( "RECEIVE") )
			{
				return execStreamReceive( props );
			}
			else
			{
				_log.debug ( "Unrecognized RAW message opcode: \""
						+ opcode + "\"" );
				return false;
			}
		}
		
		
		
		
		
		private boolean execStreamReceive ( Properties props )
		{
			if ( props == null )
			{
				_log.debug ( "No parameters specified in STREAM RECEIVE message" );
				return false;
			}

			int id;

			{
				String strid = props.getProperty ( "ID" );

				if ( strid == null )
				{
					_log.debug ( "ID not specified in STREAM RECEIVE message" );
					return false;
				}

				try
				{
					id = Integer.parseInt ( strid );
				}
				catch ( NumberFormatException e )
				{
					_log.debug ( "Invalid STREAM RECEIVE ID specified: " + strid );
					return false;
				}
			}

			boolean nolimit = false;
			
			long limit = 0;
			{
				String strsize = props.getProperty ( "LIMIT" );

				if ( strsize == null )
				{
					_log.debug ( "Limit not specified in STREAM RECEIVE message" );
					return false;
				}

				if ( strsize.equals( "NONE" ) )
				{
					nolimit = true ;
				}
				else 
				{
					try
					{
						limit = Long.parseLong ( strsize );
					}
					catch ( NumberFormatException e )
					{
						_log.debug ( "Invalid STREAM RECEIVE size specified: " + strsize );
						return false;
					}

					if ( limit < 0 )
					{
						_log.debug ( "Specified limit (" + limit
								+ ") is out of protocol limits" );
						return false;
					}
				}
			}

			getStreamSession().setReceiveLimit ( id, limit, nolimit ) ;

			return true;
		}


}
