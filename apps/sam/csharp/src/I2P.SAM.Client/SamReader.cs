using System;
using System.Collections;
using System.Collections.Specialized;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;

namespace I2P.SAM.Client
{
	/// <summary>
	///   Read from a socket, producing events for any SAM message read.
	/// </summary>
	public class SamReader
	{
		private bool                   _isLive;
		private SamClientEventListener _listener;
		private NetworkStream          _samStream;
		private StreamReader           _streamReader;

		public SamReader(NetworkStream samStream, SamClientEventListener listener) {
			_samStream = samStream;
			_listener = listener;
		}

		public void RunThread() {

			NameValueCollection parameters = new NameValueCollection();

			while (_isLive) {

				string line = null;

				_streamReader = new StreamReader(_samStream);

				try {
					line = _streamReader.ReadLine();
					_streamReader.Close();
				} catch (IOException ioe) {
					Console.Error.WriteLine("Error reading from SAM: {1}", ioe);
				} catch (OutOfMemoryException oome) {
					Console.Error.WriteLine("Out of memory while reading from SAM: {1}", oome);
					return;
				}

				if (line == null) {
					break;
				}

				string[] tokens = line.Split(new char[1] { ' ' });

				if (tokens.Length < 2) {
					Console.Error.WriteLine("Invalid SAM line: [" + line + "]");
					_isLive = false;
					return;
				}

				IEnumerator tokensEnumerator = tokens.GetEnumerator();
				tokensEnumerator.MoveNext();
				string major = (string) tokensEnumerator.Current;
				tokensEnumerator.MoveNext();
				string minor = (string) tokensEnumerator.Current;

				parameters.Clear();

				while (tokensEnumerator.MoveNext()) {

					string pair = (string) tokensEnumerator.Current;
					int equalsPosition = pair.IndexOf('=');

					if ( (equalsPosition > 0) && (equalsPosition < pair.Length - 1) ) {

						string name = pair.Substring(0, equalsPosition);
						string valueString = pair.Substring(equalsPosition + 1);

						while ( (valueString[0] == '\"') && (valueString.Length > 0) )
							valueString = valueString.Substring(1);

						while ( (valueString.Length > 0) && (valueString[valueString.Length - 1] == '\"') )
							valueString = valueString.Substring(0, valueString.Length - 1);

						parameters.Set(name, valueString);
					}
				}

				ProcessEvent(major, minor, parameters);
			}
		}

		private void ProcessEvent(string major, string minor, NameValueCollection parameters) {

			switch (major)
			{
				case "HELLO" :

					if (minor.Equals("REPLY")) {

						string result = parameters.Get("RESULT");

						if (result.Equals("OK"))
							_listener.HelloReplyReceived(true);
						else
							_listener.HelloReplyReceived(false);
					} else {
						_listener.UnknownMessageReceived(major, minor, parameters);
					}

					break;

				case "SESSION" :

					if (minor.Equals("STATUS")) {

						string result = parameters.Get("RESULT");
						string destination = parameters.Get("DESTINATION");
						string message = parameters.Get("MESSAGE");

						_listener.SessionStatusReceived(result, destination, message);
					} else {
						_listener.UnknownMessageReceived(major, minor, parameters);
					}

					break;

				case "STREAM" :

					ProcessStream(major, minor, parameters);
					break;

				case "NAMING" :

					if (minor.Equals("REPLY")) {

						string name = parameters.Get("NAME");
						string result = parameters.Get("RESULT");
						string valueString = parameters.Get("VALUE");
						string message = parameters.Get("MESSAGE");

						_listener.NamingReplyReceived(name, result, valueString, message);
					} else {
						_listener.UnknownMessageReceived(major, minor, parameters);
					}

					break;

				case "DEST" :

					if (minor.Equals("REPLY")) {

						string pub = parameters.Get("PUB");
						string priv = parameters.Get("PRIV");

						_listener.DestReplyReceived(pub, priv);
					} else {
						_listener.UnknownMessageReceived(major, minor, parameters);
					}

					break;

				default :

					_listener.UnknownMessageReceived(major, minor, parameters);
					break;
			}
		}

		private void ProcessStream(string major, string minor, NameValueCollection parameters) {

			/*
			 * Would use another tidy switch() statement here but the Mono
			 * compiler presently gets variable scopes confused within nested
			 * switch() contexts. Broken with Mono/mcs 1.0.5, 1.1.3, and SVN
			 * head.
			 */
			if (minor.Equals("STATUS")) {

				string result = parameters.Get("RESULT");
				string id = parameters.Get("ID");
				string message = parameters.Get("MESSAGE");

				try {
					_listener.StreamStatusReceived(result, Int32.Parse(id), message);
				} catch {
					_listener.UnknownMessageReceived(major, minor, parameters);
				}

			} else if (minor.Equals("CONNECTED")) {

				string destination = parameters.Get("DESTINATION");
				string id = parameters.Get("ID");

				try {
					_listener.StreamConnectedReceived(destination, Int32.Parse(id));
				} catch {
					_listener.UnknownMessageReceived(major, minor, parameters);
				}

			} else if (minor.Equals("CLOSED")) {

				string result = parameters.Get("RESULT");
				string id = parameters.Get("ID");
				string message = parameters.Get("MESSAGE");

				try {
					_listener.StreamClosedReceived(result, Int32.Parse(id), message);
				} catch {
					_listener.UnknownMessageReceived(major, minor, parameters);
				}

			} else if (minor.Equals("RECEIVED")) {

				string id = parameters.Get("ID");
				string size = parameters.Get("SIZE");

				if (id != null) {
					try {

						int idValue = Int32.Parse(id);
						int sizeValue = Int32.Parse(size);
						byte[] data = new byte[sizeValue];
						int bytesRead;

						try {
							bytesRead = _samStream.Read(data, 0, sizeValue);

							if (bytesRead != sizeValue) {
								_listener.UnknownMessageReceived(major, minor, parameters);
								return;
							}
						} catch {
							_isLive = false;
							_listener.UnknownMessageReceived(major, minor, parameters);
						}

						_listener.StreamDataReceived(idValue, data, 0, sizeValue);
					} catch (FormatException fe) {
						_listener.UnknownMessageReceived(major, minor, parameters);
					}
				} else {
					_listener.UnknownMessageReceived(major, minor, parameters);
				}
			} else {
				_listener.UnknownMessageReceived(major, minor, parameters);
			}
		}

		public void StartReading() {
			_isLive = true;
			ThreadStart threadStart = new ThreadStart(RunThread);
			Thread thread = new Thread(threadStart);
			thread.Name = "SAM Reader";
			thread.Start();
		}

		public void StopReading() {
			_isLive = false;
		}
	}
}
