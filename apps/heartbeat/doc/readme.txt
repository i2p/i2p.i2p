Heartbeat

Application layer tool for monitoring the long term health of the
network by periodically testing peers, generating stats, and
rendering them visually.  The engine (both server and client) should
work headless and seperate from the GUI, exposing the data in a simple
to parse (and human readable) text file for each peer being tested.  
The GUI then periodically refreshes itself by loading those files (
either locally or from a URL) and renders the current state accordingly, 
giving users a way to check that the network is alive, devs a tool to 
both monitor the state of the network and to debug different situations (by 
accessing the stat file - either live or archived).

The heartbeat configuration file is organized as a standard properties
file (by default located at heartbeat.config, but that can be overridden by 
passing a filename as the first argument to the Heartbeat command):

    # where the router is located (default is localhost)
    i2cpHost=localhost
    # I2CP port for the router (default is 7654)
    i2cpPort=4001
    # How many hops we want the router to put in our tunnels (default is 2)
    numHops=2
    # where our private destination keys are located - if this doesn't exist,
    # a new one will be created and saved there (by default, heartbeat.keys)
    privateDestinationFile=heartbeat_r2.keys

    ## peer tests configured below:
    
    # destination peer for test 0
    peer.0.peer=[destination in base64]
    # where will we write out the stat data?
    peer.0.statFile=heartbeatStat_khWY_30s_1kb.txt
    # how many minutes will we keep stats for?
    peer.0.statDuration=30
    # how often will we write out new stat data (in seconds)?
    peer.0.statFrequency=60
    # how often will we send a ping to the peer (in seconds)?
    peer.0.sendFrequency=30
    # how many bytes will be included in the ping?
    peer.0.sendSize=1024
    # take a guess...
    peer.0.comment=Test with localhost sending 1KB of data every 30 seconds
    # we can keep track of a few moving averages - this value includes a whitespace
    # delimited list of numbers, each specifying a period to calculate the average
    # over (in minutes)
    peer.0.averagePeriods=1 5 30
    ## repeat the peer.0.* for as many tests as desired, incrementing as necessary

If there are no peer.* lines, it will simply run a pong server.  If any data is
missing, it will use the defaults (though there are no defaults for peer.* lines) -
running the Heartbeat app with no heartbeat configuration file whatsoever will create
a new pong server (storing its keys at heartbeat.keys) and using the I2P router at 
localhost:7654.

The stat file generated for each set of peer.n.* lines contains the current state
of the test, its averages, as well as any other interesting data points.  An example
stat file follows (hopefully it is self explanatory):

    peer            khWYqCETu9YtPUvGV92ocsbEW5DezhKlIG7ci8RLX3g=
    local           u-9hlR1ik2hemXf0HvKMfeRgrS86CbNQh25e7XBhaQE=
    peerDest        [base 64 of the full destination]
    localDest       [base 64 of the full destination]
    numTunnelHops   2
    comment         Test with localhost sending 30KB every 20 seconds
    sendFrequency   20
    sendSize        30720
    sessionStart    20040409.22:51:10.915
    currentTime     20040409.23:31:39.607
    numPending      2
    lifetimeSent    118
    lifetimeRecv    113
    #averages       minutes sendMs  recvMs  numLost
    periodAverage   1       1843    771     0
    periodAverage   5       786     752     1
    periodAverage   30      855     735     3
    #action status  date and time sent      sendMs  replyMs
    EVENT   OK      20040409.23:21:44.742   691     670
    EVENT   OK      20040409.23:22:05.201   671     581
    EVENT   OK      20040409.23:22:26.301   1182    1452
    EVENT   OK      20040409.23:22:47.322   24304   1723
    EVENT   OK      20040409.23:23:08.232   2293    1081
    EVENT   OK      20040409.23:23:29.332   1392    641
    EVENT   OK      20040409.23:23:50.262   641     761
    EVENT   OK      20040409.23:24:11.102   651     701
    EVENT   OK      20040409.23:24:31.401   841     621
    EVENT   OK      20040409.23:24:52.061   651     681
    EVENT   OK      20040409.23:25:12.480   701     1623
    EVENT   OK      20040409.23:25:32.990   1442    1212
    EVENT   OK      20040409.23:25:54.230   591     631
    EVENT   OK      20040409.23:26:14.620   620     691
    EVENT   OK      20040409.23:26:35.199   1793    1432
    EVENT   OK      20040409.23:26:56.570   661     641
    EVENT   OK      20040409.23:27:17.200   641     660
    EVENT   OK      20040409.23:27:38.120   611     921
    EVENT   OK      20040409.23:27:58.699   831     621
    EVENT   OK      20040409.23:28:19.559   801     661
    EVENT   OK      20040409.23:28:40.279   601     611
    EVENT   OK      20040409.23:29:00.648   601     621
    EVENT   OK      20040409.23:29:21.288   701     661
    EVENT   LOST    20040409.23:29:41.828
    EVENT   LOST    20040409.23:30:02.327
    EVENT   LOST    20040409.23:30:22.656
    EVENT   OK      20040409.23:31:24.305   1843    771

The actual ping and pong messages sent are formatted trivially -
ping messages contain
    $from $series $type $sentOn $size $payload
while pong messages contain
    $from $series $type $sentOn $receivedOn $size $payload

$series is a number describing the sending client's test (so that you can
ping the same peer with different configurations concurrently, varying things
like the frequency and size of the message, window, etc).

They are sent as raw binary messages though, so see I2PAdapter.sendPing(..)
and I2PAdapter.sendPong(..) for the details.

To get valid measurements, of course, you will want to make sure that 
both the heartbeat client and pong server have synchronized clocks (even
more so than I2P requires).  It is highly recommended that only NTP 
synchronized peers be used for heartbeat tests.