package net.i2p.netmonitor.gui;

class NetViewerRunner implements Runnable {
    private NetViewer _viewer;
    
    public NetViewerRunner(NetViewer viewer) {
        _viewer = viewer;
    }
    
    public void run() {
        while (!_viewer.getIsClosed()) {
            _viewer.reloadData();
            try { Thread.sleep(_viewer.getDataLoadDelay()*1000); } catch (InterruptedException ie) {}
        }
    }
}