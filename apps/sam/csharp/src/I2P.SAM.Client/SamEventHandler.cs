using System;
using System.Collections.Specialized;
using System.Threading;

namespace I2P.SAM.Client
{
	/// <summary>
	///   Simple helper implementation of a SamClientEventListener.
	/// </summary>
	public class SamEventHandler : SamClientEventListenerImpl
	{
		private object              _helloLock         = new Object();
		private String              _helloOk;
		private NameValueCollection _namingReplies     = new NameValueCollection();
		private object              _namingReplyLock   = new Object();
		private object              _sessionCreateLock = new Object();
		private String              _sessionCreateOk;

		public override void HelloReplyReceived(bool ok) {
			lock (_helloLock) {
				if (ok)
					_helloOk = Boolean.TrueString;
				else
					_helloOk = Boolean.FalseString;

				Monitor.PulseAll(_helloLock);
			}
		}

		public override void NamingReplyReceived(string name, string result, string valueString, string message) {
			lock (_namingReplyLock) {
				if (result.Equals(SamBridgeMessages.NAMING_REPLY_OK))
					_namingReplies.Add(name, valueString);
				else
					_namingReplies.Add(name, result);

				Monitor.PulseAll(_namingReplyLock);
			}
		}

		public override void SessionStatusReceived(string result, string destination, string message) {
			lock (_sessionCreateLock) {
				if (result.Equals(SamBridgeMessages.SESSION_STATUS_OK))
					_sessionCreateOk = Boolean.TrueString;
				else
					_sessionCreateOk = Boolean.FalseString;

				Monitor.PulseAll(_sessionCreateLock);
			}
		}

	    public override void UnknownMessageReceived(string major, string minor, NameValueCollection parameters) {
			Console.WriteLine("wrt, [" + major + "] [" + minor + "] [" + parameters + "]");
	    }
	    
		/*
		 * Blocking lookup calls below.
		 */

		/// <summary>
		///   Wait for the connection to be established, returning true if
		///   everything went ok.
		/// </summary>
		public bool WaitForHelloReply() {
			while (true) {
				lock (_helloLock) {
					if (_helloOk == null)
						Monitor.Wait(_helloLock);
					else
						return Boolean.Parse(_helloOk);
				}
			}
		}

		/// <summary>
		///   Return the destination found matching the name, or <c>null</c> if
		///   the key was not able to be retrieved.
		/// </summary>
		/// <param name="name">The name to be looked for, or "ME".</param>
		public string WaitForNamingReply(string name) {
			while (true) {
				lock (_namingReplyLock) {
					try {
						string valueString = _namingReplies[name];
						_namingReplies.Remove(name);

						if (valueString.Equals(SamBridgeMessages.NAMING_REPLY_INVALID_KEY))
							return null;
						else if (valueString.Equals(SamBridgeMessages.NAMING_REPLY_KEY_NOT_FOUND))
							return null;
						else
							return valueString;

					} catch (ArgumentNullException ane) {
						Monitor.Wait(_namingReplyLock);
					}
				}
			}
		}

		/// <summary>
		///   Wait for the session to be created, returning true if everything
		///   went ok.
		/// </summary>
		public bool WaitForSessionCreateReply() {
			while (true) {
				lock (_sessionCreateLock) {
					if (_sessionCreateOk == null)
						Monitor.Wait(_sessionCreateLock);
					else
						return Boolean.Parse(_sessionCreateOk);
				}
			}
		}
	}
}