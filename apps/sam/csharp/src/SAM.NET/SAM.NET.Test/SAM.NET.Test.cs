using System;
using System.Net;
using System.Threading;
using System.Text;
using System.Collections;

namespace SAM.NET
{
	class SAMTester
	{
		[STAThread]
		static void Main(string[] args)
		{
			new SAMTester();
		}
		public SAMTester () 
		{
			SAMConnection connection1 = new SAMConnection(IPAddress.Parse("127.0.0.1"),7656);
			SAMSession session1 = new SAMSession(connection1,SAM.NET.SamSocketType.Stream,"alice");

			SAMConnection connection2 = new SAMConnection(IPAddress.Parse("127.0.0.1"),7656);
			SAMSession session2 = new SAMSession(connection2,SAM.NET.SamSocketType.Stream,"bob");

			SAMStream stream1 = new SAMStream(connection1,session1,233);
			stream1.Connect(session2.getKey());

			//Wait till we are connected to destination
			while (!stream1.isConnected) 
				Thread.Sleep(1000);
			
			//Send some bytes
			stream1.Write(Encoding.ASCII.GetBytes(DateTime.Now.ToLongTimeString() + "Hi!!!!!!"));

			//Wait till a stream magically appears on the other side 
			while (session2.getStreams().Count == 0) Thread.Sleep(1000);

			Thread.Sleep(1000);
			while (true) {}
			foreach (SAMStream stream in session2.getStreams().Values) 
			{
				Console.WriteLine("Text received on " + stream.getID() + " at " + DateTime.Now.ToLongTimeString());
				Console.WriteLine(Encoding.ASCII.GetString(stream.ReadToEnd()));
				stream.Close();
			}
			while (true) {}

			stream1.Close();
			connection1.Close();
			connection2.Close();
		}
	}
}
