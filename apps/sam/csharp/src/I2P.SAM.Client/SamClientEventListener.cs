using System.Collections.Specialized;

namespace I2P.SAM.Client
{
	/// <summary>
	///   Async event notification interface for SAM clients.
	/// </summary>
	public interface SamClientEventListener
	{
		void HelloReplyReceived(bool ok);
		void SessionStatusReceived(string result, string destination, string message);
		void StreamStatusReceived(string result, int id, string message);
		void StreamConnectedReceived(string remoteDestination, int id);
		void StreamClosedReceived(string result, int id, string message);
		void StreamDataReceived(int id, byte[] data, int offset, int length);
		void NamingReplyReceived(string name, string result, string valueString, string message);
		void DestReplyReceived(string publicKey, string privateKey);
		void UnknownMessageReceived(string major, string minor, NameValueCollection parameters);
	}
}