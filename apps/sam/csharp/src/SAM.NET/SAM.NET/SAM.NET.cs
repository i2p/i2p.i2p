using System;
using System.Net.Sockets;
using System.Text;
using System.Net;
using System.IO;
using System.Collections;
using System.Threading;

namespace SAM.NET
{
	public enum SamSocketType 
	{
		Stream,
		Datagram,
		Raw
	} 

	public class SAMConnection
	{
		private const string propertyMinVersion = "1.0";
		private const string propertyMaxVersion = "1.0";

		private Socket _sock;
		private NetworkStream _sockStream;
		private StreamReader _sockStreamIn;
		private StreamWriter _sockStreamOut;

		public SAMConnection(IPAddress routerIP, int port)
		{
			_sock = new Socket(AddressFamily.InterNetwork,SocketType.Stream,ProtocolType.Tcp);
			IPEndPoint rEP = new IPEndPoint(routerIP,port);
			_sock.Connect(rEP);
			_sockStream = new NetworkStream(_sock);
			_sockStreamIn = new StreamReader(_sockStream);
			_sockStreamOut = new StreamWriter(_sockStream);
			try 
			{
				sendVersion(propertyMinVersion,propertyMinVersion);
			}
			catch (Exception e) 
			{
				_sock.Close();
				throw (new Exception("No SAM for you :("));
			}
			
		}

		private void sendVersion(string min, string max) 
		{
			_sockStreamOut.WriteLine("HELLO VERSION MIN=" + propertyMinVersion + " MAX=" + propertyMaxVersion);
			_sockStreamOut.Flush();
			Hashtable response = SAMUtil.parseKeyValues(_sockStreamIn.ReadLine(),2);
			if (response["RESULT"].ToString() != "OK") throw (new Exception("Version mismatch"));
		}

		public StreamWriter getOutputStream() 
		{
			return _sockStreamOut;
		}

		public StreamReader getInputStream() 
		{
			return _sockStreamIn;
		}

		public NetworkStream getStream() 
		{
			return _sockStream;
		}

		public void Close() 
		{
			_sock.Close();
		}
	}

	/*
	 * Creating a SAMSession object will automatically:
	 * 1) create a sesion on SAM
	 * 1) query for the base64key
	 * 2) start a listening thread to catch all stream commands
	 */
	public class SAMSession 
	{
		private Hashtable _streams;
		private string _sessionKey;
		
		public SAMSession (SAMConnection connection, SamSocketType type, string destination) 
		{
			_streams = new Hashtable();
			StreamWriter writer = connection.getOutputStream();
			StreamReader reader = connection.getInputStream();
			writer.WriteLine("SESSION CREATE STYLE=STREAM DESTINATION=" + destination);
			writer.Flush();
			Hashtable response = SAMUtil.parseKeyValues(reader.ReadLine(),2);
			if (response["RESULT"].ToString() != "OK") 
			{
				throw (new Exception(response["MESSAGE"].ToString()));
			}
			else 
			{
				writer.WriteLine("NAMING LOOKUP NAME=ME");
				writer.Flush();
				response = SAMUtil.parseKeyValues(reader.ReadLine(),2);
				_sessionKey = response["VALUE"].ToString();
				SAMSessionListener listener = new SAMSessionListener(connection,this,_streams);
				new Thread(new ThreadStart(listener.startListening)).Start();
			}
		}
		public void addStream(SAMStream stream) 
		{
			_streams.Add(stream.getID(),stream);
		}
		public string getKey() 
		{
			return _sessionKey;
		}
		public Hashtable getStreams() 
		{
			return _streams;
		}
	}

	public class SAMSessionListener
	{
		private Hashtable _streams;
		private SAMConnection _connection;
		private SAMSession _session;
		private bool stayAlive = true;

		public SAMSessionListener(SAMConnection connection,SAMSession session, Hashtable streams) 
		{
			_streams = streams;
			_connection = connection;
			_session = session;
		}
		public void startListening() 
		{
			StreamReader reader = _connection.getInputStream();
			while (stayAlive) 
			{
				string response = reader.ReadLine();
				if (response.StartsWith("STREAM STATUS")) 
				{
					Hashtable values = SAMUtil.parseKeyValues(response,2);
					SAMStream theStream = (SAMStream)_streams[int.Parse(values["ID"].ToString())];
					if (theStream != null) theStream.ReceivedStatus(values);
				}
				if (response.StartsWith("STREAM CONNECTED")) 
				{
					Hashtable values = SAMUtil.parseKeyValues(response,2);
					SAMStream theStream = (SAMStream)_streams[int.Parse(values["ID"].ToString())];
					if (theStream != null) theStream.isConnected = true;
				}
				if (response.StartsWith("STREAM RECEIVED")) 
				{
					Hashtable values = SAMUtil.parseKeyValues(response,2);
					int streamID = int.Parse(values["ID"].ToString());
					SAMStream theStream = (SAMStream)_streams[streamID];
					if (theStream == null) new SAMStream(_connection,_session,streamID);  
					theStream = (SAMStream)_streams[streamID];
					theStream.ReceivedData(int.Parse(values["SIZE"].ToString()));
				}
				if (response.StartsWith("STREAM CLOSE")) 
				{
					Hashtable values = SAMUtil.parseKeyValues(response,2);
					SAMStream theStream = (SAMStream)_streams[int.Parse(values["ID"].ToString())];
					if (theStream != null) theStream.isConnected = false;
				}
			}
		}
	}

	public class SAMStream 
	{
		private int _ID;
		private byte[] _data;
		private int _position=0;
		private int _size=0;
		private SAMSession _session;
		private SAMConnection _connection;
		public bool isConnected=false;
		
		public SAMStream (SAMConnection connection,SAMSession session, int ID) 
		{
			_data = new byte[100000]; //FIXME: change to non-static structure for storing stream data
			_ID = ID;
			_connection = connection;
			_session = session;
			_session.addStream(this);
		}

		public void Connect(string destination) 
		{
			StreamWriter writer = _connection.getOutputStream();
			writer.WriteLine("STREAM CONNECT ID=" + _ID.ToString() + " DESTINATION=" + destination);
			writer.Flush();
		}

		public void ReceivedData(int size) //FIXME: WTF is going on when reading the payload here? All zeros and way too many of them.
		{
			NetworkStream stream = _connection.getStream();
			int bytesRead = stream.Read(_data,_size,size);
			_size = _size + bytes;
		}

		public void ReceivedStatus(Hashtable response) 
		{
			if (response["RESULT"].ToString() != "OK") 
			{
				throw (new Exception(response["RESULT"].ToString()));
			}
			else 
			{
				isConnected = true;
			}
		}

		public int getID() {return _ID;}

		public bool DataAvailable() 
		{
			return _position != _size;
		}

		public void Write(byte[] buf) 
		{
			NetworkStream stream = _connection.getStream();
			int sent = 0;
			while (sent < buf.Length) 
			{
				int toSend = Math.Min(buf.Length - sent,32768);
				string header = "STREAM SEND ID=" + _ID.ToString() + " SIZE=" + toSend.ToString() + "\n";
				byte[] headerbytes = Encoding.ASCII.GetBytes(header);
				stream.Write(headerbytes,0,headerbytes.Length);
				stream.Write(buf,sent,toSend);
				sent = sent + toSend;
			}
		}

		public byte[] ReadToEnd() 
		{
			byte[] ret = new byte[_size - _position];
			Array.Copy(_data,_position,ret,0,_size - _position);
			_position = _size;
			return ret;
		}

		public void Close() 
		{
			StreamWriter writer = _connection.getOutputStream();
			writer.WriteLine("STREAM CLOSE " + _ID.ToString());
			writer.Flush();
		}
	}

	public class SAMUtil 
	{
		public static Hashtable parseKeyValues(string str, int startingWord) 
		{
			Hashtable hash = new Hashtable();
			string strTruncated = string.Join(" ",str.Split(' '),startingWord,str.Split(' ').Length - startingWord);
			string[] sets = strTruncated.Split('=',' ');
			for (int i=0; i<sets.Length; i=i+2)
			{
				hash.Add(sets[i],sets[i+1]);
			}
			return hash;
		}
	}
}
