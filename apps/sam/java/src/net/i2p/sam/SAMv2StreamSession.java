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
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.ByteBuffer;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import net.i2p.I2PException;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketOptions;
import net.i2p.data.ByteArray;
import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.ByteCache;
import net.i2p.util.I2PAppThread;
import net.i2p.util.Log;

/**
 * SAMv2 STREAM session class.
 *
 * @author mkvore
 */

public class SAMv2StreamSession extends SAMStreamSession
{

		private final static Log _log = new Log ( SAMv2StreamSession.class );

		/**
		 * Create a new SAM STREAM session.
		 *
		 * @param dest Base64-encoded destination (private key)
		 * @param dir Session direction ("RECEIVE", "CREATE" or "BOTH")
		 * @param props Properties to setup the I2P session
		 * @param recv Object that will receive incoming data
		 * @throws IOException
		 * @throws DataFormatException
		 * @throws SAMException 
		 */
		public SAMv2StreamSession ( String dest, String dir, Properties props,
		                            SAMStreamReceiver recv ) throws IOException, DataFormatException, SAMException
		{
			super ( dest, dir, props, recv );
		}

		/**
		 * Create a new SAM STREAM session.
		 *
		 * @param destStream Input stream containing the destination keys
		 * @param dir Session direction ("RECEIVE", "CREATE" or "BOTH")
		 * @param props Properties to setup the I2P session
		 * @param recv Object that will receive incoming data
		 * @throws IOException
		 * @throws DataFormatException
		 * @throws SAMException 
		 */
		public SAMv2StreamSession ( InputStream destStream, String dir,
		                            Properties props,  SAMStreamReceiver recv ) throws IOException, DataFormatException, SAMException
		{
			super ( destStream, dir, props, recv );
		}

		/**
		 * Connect the SAM STREAM session to the specified Destination
		 *
		 * @param id Unique id for the connection
		 * @param dest Base64-encoded Destination to connect to
		 * @param props Options to be used for connection
		 *
		 * @throws DataFormatException if the destination is not valid
		 * @throws SAMInvalidDirectionException if trying to connect through a
		 *                                      receive-only session
		 * @return true if the communication with the SAM client is ok
		 */

		@Override
		public boolean connect ( int id, String dest, Properties props )
		throws DataFormatException, SAMInvalidDirectionException
		{
			if ( !canCreate )
			{
				_log.debug ( "Trying to create an outgoing connection using a receive-only session" );
				throw new SAMInvalidDirectionException ( "Trying to create connections through a receive-only session" );
			}

			if ( checkSocketHandlerId ( id ) )
			{
				_log.debug ( "The specified id (" + id + ") is already in use" );
				return false ;
			}

			Destination d = new Destination();

			d.fromBase64 ( dest );

			I2PSocketOptions opts = socketMgr.buildOptions ( props );

			if ( props.getProperty ( I2PSocketOptions.PROP_CONNECT_TIMEOUT ) == null )
				opts.setConnectTimeout ( 60 * 1000 );

			_log.debug ( "Connecting new I2PSocket..." );


			// non-blocking connection (SAMv2)

			StreamConnector connector ;

			connector = new StreamConnector ( id, d, opts );
			
			I2PAppThread connectThread = new I2PAppThread ( connector, "StreamConnector" + id ) ;

			connectThread.start() ;

			return true ;
		}




		/**
				* SAM STREAM socket connecter, running in its own thread.  
				*
				* @author mkvore
		*/

		public class StreamConnector implements Runnable
		{

				private int id;
				private Destination      dest ;
				private I2PSocketOptions opts ;

				/**
						* Create a new SAM STREAM session socket reader
						*
						* @param id   Unique id assigned to the handler
						* @param dest Destination to reach
						* @param opts Socket options (I2PSocketOptions)
				*/


				public StreamConnector ( int id, Destination dest, I2PSocketOptions opts )// throws IOException
				{
					_log.debug ( "Instantiating new SAM STREAM connector" );

					this.id   = id ;
					this.opts = opts ;
					this.dest = dest ;
				}


				public void run()
				{
					_log.debug ( "run() called for socket connector " + id );

					try
					{
						try
						{
							I2PSocket i2ps = socketMgr.connect ( dest, opts );

							createSocketHandler ( i2ps, id );

							recv.notifyStreamOutgoingConnection ( id, "OK", null );
						}

						catch ( DataFormatException e )
						{
							_log.debug ( "Invalid destination in STREAM CONNECT message" );
							recv.notifyStreamOutgoingConnection ( id, "INVALID_KEY", e.getMessage() );
						}
						catch ( ConnectException e )
						{
							_log.debug ( "STREAM CONNECT failed: " + e.getMessage() );
							recv.notifyStreamOutgoingConnection ( id, "CONNECTION_REFUSED", e.getMessage() );
						}
						catch ( NoRouteToHostException e )
						{
							_log.debug ( "STREAM CONNECT failed: " + e.getMessage() );
							recv.notifyStreamOutgoingConnection ( id, "CANT_REACH_PEER", e.getMessage() );
						}
						catch ( InterruptedIOException e )
						{
							_log.debug ( "STREAM CONNECT failed: " + e.getMessage() );
							recv.notifyStreamOutgoingConnection ( id, "TIMEOUT", e.getMessage() );
						}
						catch ( I2PException e )
						{
							_log.debug ( "STREAM CONNECT failed: " + e.getMessage() );
							recv.notifyStreamOutgoingConnection ( id, "I2P_ERROR", e.getMessage() );
						}
					}
					catch ( IOException e )
					{
						_log.debug ( "Error sending disconnection notice for handler "
						             + id, e );
					}

					_log.debug ( "Shutting down SAM STREAM session connector " + id );
				}
		}



		/**
				* Lets us push data through the stream without blocking, (even after exceeding
				* the I2PSocket's buffer)
		 * 
		 * @param s I2PSocket
		 * @param id Socket ID
		 * @return v2StreamSender
		 * @throws IOException 
		 */

		@Override
		protected StreamSender newStreamSender ( I2PSocket s, int id ) throws IOException
		{
			return new v2StreamSender ( s, id ) ;
		}

		@Override
		protected SAMStreamSessionSocketReader 
				newSAMStreamSessionSocketReader(I2PSocket s, int id ) throws IOException
		{
			return new SAMv2StreamSessionSocketReader(s,id);
		}

		protected class v2StreamSender extends StreamSender

		{
				private List<ByteArray> _data;
				private int _dataSize;
				private int _id;
				private ByteCache _cache;
				private OutputStream _out = null;
				private boolean _stillRunning, _shuttingDownGracefully;
				private Object runningLock = new Object();
				private I2PSocket i2pSocket = null;

				public v2StreamSender ( I2PSocket s, int id ) throws IOException
				{
					super ( s, id );
					_data = new ArrayList<ByteArray> ( 1 );
					_dataSize = 0;
					_id = id;
					_cache = ByteCache.getInstance ( 10, 32 * 1024 );
					_out = s.getOutputStream();
					_stillRunning = true;
					_shuttingDownGracefully = false;
					i2pSocket = s;
				}

				/**
						* Send bytes through the SAM STREAM session socket sender
						*
				 * @param in Data stream of data to send
				 * @param size Count of bytes to send
				 * @throws IOException if the client didnt provide enough data
				*/
				@Override
				public void sendBytes ( InputStream in, int size ) throws IOException
				{
					if ( _log.shouldLog ( Log.DEBUG ) )
						_log.debug ( "Handler " + _id + ": sending " + size + " bytes" );

					ByteArray ba = _cache.acquire();

					int read = DataHelper.read ( in, ba.getData(), 0, size );

					if ( read != size )
						throw new IOException ( "Insufficient data from the SAM client (" + read + "/" + size + ")" );

					ba.setValid ( read );

					synchronized ( _data )
					{
						if ( _dataSize >= SOCKET_HANDLER_BUF_SIZE )
						{
							_cache.release ( ba, false );
							recv.streamSendAnswer ( _id, "FAILED", "BUFFER_FULL" ) ;
						}
						else
						{
							_dataSize += size ;
							_data.add ( ba );
							_data.notifyAll();

							if ( _dataSize >= SOCKET_HANDLER_BUF_SIZE )
							{
								recv.streamSendAnswer ( _id, "OK", "BUFFER_FULL" ) ;
							}
							else
							{
								recv.streamSendAnswer ( _id, "OK", "READY" );
							}
						}
					}
				}

				/**
						* Stop a SAM STREAM session socket sender thread immediately
						*
				*/
				@Override
				public void stopRunning()
				{
					_log.debug ( "stopRunning() invoked on socket sender " + _id );

					synchronized ( runningLock )
					{
						if ( _stillRunning )
						{
							_stillRunning = false;

							try
							{
								i2pSocket.close();
							}
							catch ( IOException e )
							{
								_log.debug ( "Caught IOException", e );
							}

							synchronized ( _data )
							{
								_data.clear();
								_data.notifyAll();
							}
						}
					}
				}

				/**
						* Stop a SAM STREAM session socket sender gracefully: stop the
						* sender thread once all pending data has been sent.
				*/
				@Override
				public void shutDownGracefully()
				{
					_log.debug ( "shutDownGracefully() invoked on socket sender " + _id );
					_shuttingDownGracefully = true;
				}

				@Override
				public void run()
				{
					_log.debug ( "run() called for socket sender " + _id );
					ByteArray data = null;

					while ( _stillRunning )
					{
						data = null;

						try
						{
							synchronized ( _data )
							{
								if ( _data.size() > 0 )
								{
									int formerSize = _dataSize ;
									data = ( ByteArray ) _data.remove ( 0 );
									_dataSize -= data.getValid();

									if ( ( formerSize >= SOCKET_HANDLER_BUF_SIZE ) && ( _dataSize < SOCKET_HANDLER_BUF_SIZE ) )
										recv.notifyStreamSendBufferFree ( _id );
								}
								else if ( _shuttingDownGracefully )
								{
									/* No data left and shutting down gracefully?
									If so, stop the sender. */
									stopRunning();
									break;
								}
								else
								{
									/* Wait for data. */
									_data.wait ( 5000 );
								}
							}

							if ( data != null )
							{
								try
								{
									_out.write ( data.getData(), 0, data.getValid() );

									if ( forceFlush )
									{
										// i dont like doing this, but it clears the buffer issues
										_out.flush();
									}
								}
								catch ( IOException ioe )
								{
									// ok, the stream failed, but the SAM client didn't

									if ( _log.shouldLog ( Log.WARN ) )
										_log.warn ( "Stream failed", ioe );

									removeSocketHandler ( _id );

									stopRunning();

								}
								finally
								{
									_cache.release ( data, false );
								}
							}
						}
						catch ( InterruptedException ie ) {}
						catch ( IOException e ) {}}

					synchronized ( _data )
					{
						_data.clear();
					}
				}
		}



		/**
		 * Send bytes through a SAM STREAM session.
		 *
		 * @param id Stream ID
		 * @param limit limitation
		 * @param nolimit true to limit
		 * @return True if the data was queued for sending, false otherwise
		*/
		@Override
		public boolean setReceiveLimit ( int id, long limit, boolean nolimit )
		{
			SAMStreamSessionSocketReader reader = getSocketReader ( id );

			if ( reader == null )
			{
				if ( _log.shouldLog ( Log.WARN ) )
					_log.warn ( "Trying to set a limit to a nonexistent reader " + id );

				return false;
			}

			( (SAMv2StreamSessionSocketReader) reader).setLimit ( limit, nolimit );

			return true;
		}


		/**
				* SAM STREAM socket reader, running in its own thread.  It forwards
				* forward data to/from an I2P socket.
				*
				* @author human
		*/

		

		public class SAMv2StreamSessionSocketReader extends SAMv1StreamSessionSocketReader
		{

				protected boolean nolimit       ;
				protected long    limit         ;
				protected long    totalReceived ;


				/**
						* Create a new SAM STREAM session socket reader
						*
						* @param s Socket to be handled
						* @param id Unique id assigned to the handler
				*/
				public SAMv2StreamSessionSocketReader ( I2PSocket s, int id ) throws IOException
				{
					super ( s, id );
					nolimit       = false ;
					limit         = 0     ;
					totalReceived = 0     ;
				}

				public void setLimit ( long limit, boolean nolimit )
				{
					synchronized (runningLock)
					{
						this.limit   = limit    ;
						this.nolimit = nolimit  ;
						runningLock.notify() ;
					}
					_log.debug ( "new limit set for socket reader " + id + " : " + (nolimit ? "NOLIMIT" : limit + " bytes" ) );
				}

				public void run()
				{
					_log.debug ( "run() called for socket reader " + id );

					int read = -1;
					ByteBuffer data = ByteBuffer.allocateDirect(SOCKET_HANDLER_BUF_SIZE);

					try
					{
						InputStream in = i2pSocket.getInputStream();

						while ( stillRunning )
						{
							synchronized (runningLock)
							{
								while ( stillRunning && ( !nolimit && totalReceived >= limit) )
								{
									try{
										runningLock.wait() ;
									}
									catch (InterruptedException ie)
									{}
								}
								if ( !stillRunning )
									break ;
							}
							
							data.clear();
							read = Channels.newChannel(in).read ( data );

							if ( read == -1 )
							{
								_log.debug ( "Handler " + id + ": connection closed" );
								break;
							}

							totalReceived += read ;
							data.flip();
							recv.receiveStreamBytes ( id, data );
						}
					}
					catch ( IOException e )
					{
						_log.debug ( "Caught IOException", e );
					}

					try
					{
						i2pSocket.close();
					}
					catch ( IOException e )
					{
						_log.debug ( "Caught IOException", e );
					}

					if ( stillRunning )
					{
						removeSocketHandler ( id );
						// FIXME: we need error reporting here!

						try
						{
							recv.notifyStreamDisconnection ( id, "OK", null );
						}
						catch ( IOException e )
						{
							_log.debug ( "Error sending disconnection notice for handler "
							             + id, e );
						}
					}

					_log.debug ( "Shutting down SAM STREAM session socket handler " + id );
				}
		}



}
