namespace I2P.SAM.Client
{
	public struct SamBridgeMessages
	{
		public const string NAMING_REPLY_INVALID_KEY      = "INVALID_KEY";
		public const string NAMING_REPLY_KEY_NOT_FOUND    = "KEY_NOT_FOUND";
		public const string NAMING_REPLY_OK               = "OK";

		public const string SESSION_STATUS_DUPLICATE_DEST = "DUPLICATE_DEST";
		public const string SESSION_STATUS_I2P_ERROR      = "I2P_ERROR";
		public const string SESSION_STATUS_INVALID_KEY    = "INVALID_KEY";
		public const string SESSION_STATUS_OK             = "OK";

		public const string STREAM_CLOSED_CANT_REACH_PEER = "CANT_REACH_PEER";
		public const string STREAM_CLOSED_I2P_ERROR       = "I2P_ERROR";
		public const string STREAM_CLOSED_PEER_NOT_FOUND  = "PEER_NOT_FOUND";
		public const string STREAM_CLOSED_TIMEOUT         = "CLOSED";
		public const string STREAM_CLOSED_OK              = "OK";

		public const string STREAM_STATUS_CANT_REACH_PEER = "CANT_REACH_PEER";
		public const string STREAM_STATUS_I2P_ERROR       = "I2P_ERROR";
		public const string STREAM_STATUS_INVALID_KEY     = "INVALID_KEY";
		public const string STREAM_STATUS_TIMEOUT         = "TIMEOUT";
		public const string STREAM_STATUS_OK              = "OK";
	}
}
