using System.Collections.Specialized;

namespace I2P.SAM.Client
{
	/// <summary>
	///   Basic noop client event listener.
	/// </summary>
	public class SamClientEventListenerImpl : SamClientEventListener
	{
		public virtual void DestReplyReceived(string publicKey, string privateKey) {}
		public virtual void HelloReplyReceived(bool ok) {}
		public virtual void NamingReplyReceived(string name, string result, string valueString, string message) {}
		public virtual void SessionStatusReceived(string result, string destination, string message) {}
		public virtual void StreamClosedReceived(string result, int id, string message) {}
		public virtual void StreamConnectedReceived(string remoteDestination, int id) {}
		public virtual void StreamDataReceived(int id, byte[] data, int offset, int length) {}
		public virtual void StreamStatusReceived(string result, int id, string message) {}
		public virtual void UnknownMessageReceived(string major, string minor, NameValueCollection parameters) {}
	}
}
