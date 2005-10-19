/* TrackerShutdown - Makes sure everything ends correctly when shutting down.
   Copyright (C) 2003 Mark J. Wielaard

   This file is part of Snark.
   
   This program is free software; you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation; either version 2, or (at your option)
   any later version.
 
   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
 
   You should have received a copy of the GNU General Public License
   along with this program; if not, write to the Free Software Foundation,
   Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.klomp.snark;

import java.io.IOException;

/**
 * Makes sure everything ends correctly when shutting down.
 */
public class SnarkShutdown extends Thread
{
  private final Storage storage;
  private final PeerCoordinator coordinator;
  private final ConnectionAcceptor acceptor;
  private final TrackerClient trackerclient;

  private final ShutdownListener listener;

  public SnarkShutdown(Storage storage,
                       PeerCoordinator coordinator,
                       ConnectionAcceptor acceptor,
                       TrackerClient trackerclient,
                       ShutdownListener listener)
  {
    this.storage = storage;
    this.coordinator = coordinator;
    this.acceptor = acceptor;
    this.trackerclient = trackerclient;
    this.listener = listener;
  }

  public void run()
  {
    Snark.debug("Shutting down...", Snark.NOTICE);

    Snark.debug("Halting ConnectionAcceptor...", Snark.INFO);
    if (acceptor != null)
      acceptor.halt();

    Snark.debug("Halting TrackerClient...", Snark.INFO);
    if (trackerclient != null)
      trackerclient.halt();

    Snark.debug("Halting PeerCoordinator...", Snark.INFO);
    if (coordinator != null)
      coordinator.halt();

    Snark.debug("Closing Storage...", Snark.INFO);
    if (storage != null)
      {
        try
          {
            storage.close();
          }
        catch(IOException ioe)
          {
            Snark.fatal("Couldn't properly close storage", ioe);
          }
      }

    // XXX - Should actually wait till done...
    try
      {
        Snark.debug("Waiting 5 seconds...", Snark.INFO);
        Thread.sleep(5*1000);
      }
    catch (InterruptedException ie) { /* ignored */ }

    listener.shutdown();
  }
}
