/*
 * Created by Angel Leon (@gubatron), Alden Torres (aldenml)
 * Copyright (c) 2011-2016, FrostWire(R). All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.frostwire.bittorrent;

import com.frostwire.jlibtorrent.*;
import com.frostwire.jlibtorrent.alerts.*;
import com.frostwire.jlibtorrent.swig.*;
import com.frostwire.platform.FileSystem;
import com.frostwire.platform.Platforms;
import com.frostwire.search.torrent.TorrentCrawledSearchResult;
import com.frostwire.util.Logger;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.FilenameFilter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.frostwire.jlibtorrent.alerts.AlertType.*;

/**
 * @author gubatron
 * @author aldenml
 */
public final class BTEngine extends SessionManager {

    private static final Logger LOGGER = Logger.getLogger(BTEngine.class);

    private static final int[] INNER_LISTENER_TYPES = new int[]{TORRENT_ADDED.swig(),
            PIECE_FINISHED.swig(),
            PORTMAP.swig(),
            PORTMAP_ERROR.swig(),
            DHT_STATS.swig(),
            STORAGE_MOVED.swig(),
            LISTEN_SUCCEEDED.swig(),
            LISTEN_FAILED.swig(),
            EXTERNAL_IP.swig(),
            METADATA_RECEIVED.swig()
    };

    private static final String TORRENT_ORIG_PATH_KEY = "torrent_orig_path";
    public static BTContext ctx;

    private final ReentrantLock sync;
    private final InnerListener innerListener;
    private final Queue<RestoreDownloadTask> restoreDownloadsQueue;

    private BTEngineListener listener;

    private BTEngine() {
        this.sync = new ReentrantLock();
        this.innerListener = new InnerListener();
        this.restoreDownloadsQueue = new LinkedList<>();
    }

    private static class Loader {
        static final BTEngine INSTANCE = new BTEngine();
    }

    public static BTEngine getInstance() {
        if (ctx == null) {
            throw new IllegalStateException("Context can't be null");
        }
        return Loader.INSTANCE;
    }

    public Session getSession() {
        return isRunning() ? new Session(swig()) : null;
    }

    public BTEngineListener getListener() {
        return listener;
    }

    public void setListener(BTEngineListener listener) {
        this.listener = listener;
    }

    public long getDownloadRate() {
        return super.downloadRate();
    }

    public long getUploadRate() {
        return super.uploadRate();
    }

    public long getTotalDownload() {
        return super.totalDownload();
    }

    public long getTotalUpload() {
        return super.totalUpload();
    }

    public int getDownloadRateLimit() {
        return super.downloadSpeedLimit();
    }

    public int getUploadRateLimit() {
        return super.uploadSpeedLimit();
    }

    public boolean isStarted() {
        return isRunning();
    }

    public void start() {
        if (isRunning()) {
            return;
        }

        sync.lock();

        try {
            if (isRunning()) {
                return;
            }

            settings_pack sp = new settings_pack();
            sp.set_str(settings_pack.string_types.listen_interfaces.swigValue(), ctx.interfaces);
            sp.set_int(settings_pack.int_types.max_retry_port_bind.swigValue(), ctx.retries);

            addListener(innerListener);

            super.start(new SettingsPack(sp));

            loadSettings();
            fireStarted();
        } finally {
            sync.unlock();
        }
    }

    /**
     * Abort and destroy the internal libtorrent session.
     */
    public void stop() {
        if (!isRunning()) {
            return;
        }

        sync.lock();

        try {
            if (!isRunning()) {
                return;
            }

            removeListener(innerListener);
            saveSettings();

            super.stop();

            fireStopped();

        } finally {
            sync.unlock();
        }
    }

    public void restart() {
        sync.lock();

        try {

            super.restart();

        } finally {
            sync.unlock();
        }
    }

    public void updateSavePath(File dataDir) {
        if (!isRunning()) {
            return;
        }

        ctx.dataDir = dataDir; // this will be removed when we start using platform

        moveStorage(dataDir);
    }

    public void loadSettings() {
        if (!isRunning()) {
            return;
        }

        try {
            File f = settingsFile();
            if (f.exists()) {
                byte[] data = FileUtils.readFileToByteArray(f);
                loadState(data);
            } else {
                revertToDefaultConfiguration();
            }
        } catch (Throwable e) {
            LOGGER.error("Error loading session state", e);
        }
    }

    public void saveSettings() {
        if (!isRunning()) {
            return;
        }

        try {
            byte[] data = saveState();
            FileUtils.writeByteArrayToFile(settingsFile(), data);
        } catch (Throwable e) {
            LOGGER.error("Error saving session state", e);
        }
    }

    private void saveSettings(SettingsPack sp) {
        if (!isRunning()) {
            return;
        }
        applySettings(sp);
        saveSettings();
    }

    public void revertToDefaultConfiguration() {
        if (!isRunning()) {
            return;
        }

        SettingsPack sp = settings();

        sp.broadcastLSD(true);

        if (ctx.optimizeMemory) {
            int maxQueuedDiskBytes = sp.maxQueuedDiskBytes();
            sp.setMaxQueuedDiskBytes(maxQueuedDiskBytes / 2);
            int sendBufferWatermark = sp.sendBufferWatermark();
            sp.setSendBufferWatermark(sendBufferWatermark / 2);
            sp.setCacheSize(256);
            sp.activeDownloads(4);
            sp.activeSeeds(4);
            sp.maxPeerlistSize(200);
            sp.setTickInterval(1000);
            sp.setInactivityTimeout(60);
            sp.setSeedingOutgoingConnections(false);
            sp.connectionsLimit(200);
        } else {
            sp.activeDownloads(10);
            sp.activeSeeds(10);
        }

        applySettings(sp);
        saveSettings();
    }

    public void download(File torrent, File saveDir) {
        download(torrent, saveDir, null);
    }

    public void download(File torrent, File saveDir, boolean[] selection) {
        if (session == null) {
            return;
        }

        saveDir = setupSaveDir(saveDir);
        if (saveDir == null) {
            return;
        }

        TorrentInfo ti = new TorrentInfo(torrent);

        Priority[] priorities = null;

        TorrentHandle th = downloader.find(ti.infoHash());
        boolean exists = th != null;

        if (selection != null) {
            if (th != null) {
                priorities = th.getFilePriorities();
            } else {
                priorities = Priority.array(Priority.IGNORE, ti.numFiles());
            }

            for (int i = 0; i < selection.length; i++) {
                if (selection[i]) {
                    priorities[i] = Priority.NORMAL;
                }
            }
        }

        download(ti, saveDir, priorities, null, null);

        if (!exists) {
            saveResumeTorrent(torrent);
        }
    }

    public void download(TorrentInfo ti, File saveDir, boolean[] selection, String magnetUrlParams) {
        if (session == null) {
            return;
        }

        saveDir = setupSaveDir(saveDir);
        if (saveDir == null) {
            return;
        }

        Priority[] priorities = null;

        TorrentHandle th = downloader.find(ti.infoHash());
        boolean torrentHandleExists = th != null;

        if (selection != null) {
            if (torrentHandleExists) {
                priorities = th.getFilePriorities();
            } else {
                priorities = Priority.array(Priority.IGNORE, ti.numFiles());
            }

            if (priorities != null) {
                for (int i = 0; i < selection.length; i++) {
                    if (selection[i] && i < priorities.length) {
                        priorities[i] = Priority.NORMAL;
                    }
                }
            }
        }

        download(ti, saveDir, priorities, null, magnetUrlParams);

        if (!torrentHandleExists) {
            File torrent = saveTorrent(ti);
            saveResumeTorrent(torrent);
        }
    }

    public void download(TorrentCrawledSearchResult sr, File saveDir) {
        if (session == null) {
            return;
        }

        saveDir = setupSaveDir(saveDir);
        if (saveDir == null) {
            return;
        }

        TorrentInfo ti = sr.getTorrentInfo();
        int fileIndex = sr.getFileIndex();

        TorrentHandle th = downloader.find(ti.infoHash());
        boolean exists = th != null;

        if (th != null) {
            Priority[] priorities = th.getFilePriorities();
            if (priorities[fileIndex] == Priority.IGNORE) {
                priorities[fileIndex] = Priority.NORMAL;
                download(ti, saveDir, priorities, null, null);
            }
        } else {
            Priority[] priorities = Priority.array(Priority.IGNORE, ti.numFiles());
            priorities[fileIndex] = Priority.NORMAL;
            download(ti, saveDir, priorities, null, null);
        }

        if (!exists) {
            File torrent = saveTorrent(ti);
            saveResumeTorrent(torrent);
        }
    }

    public void restoreDownloads() {
        if (session == null) {
            return;
        }

        if (ctx.homeDir == null || !ctx.homeDir.exists()) {
            LOGGER.warn("Wrong setup with BTEngine home dir");
            return;
        }

        File[] torrents = ctx.homeDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name != null && FilenameUtils.getExtension(name).toLowerCase().equals("torrent");
            }
        });

        if (torrents != null) {
            for (File t : torrents) {
                try {
                    String infoHash = FilenameUtils.getBaseName(t.getName());
                    if (infoHash != null) {
                        File resumeFile = resumeDataFile(infoHash);

                        File savePath = readSavePath(infoHash);
                        if (setupSaveDir(savePath) == null) {
                            LOGGER.warn("Can't create data dir or mount point is not accessible");
                            return;
                        }

                        restoreDownloadsQueue.add(new RestoreDownloadTask(t, null, null, resumeFile));
                    }
                } catch (Throwable e) {
                    LOGGER.error("Error restoring torrent download: " + t, e);
                }
            }
        }

        migrateVuzeDownloads();

        runNextRestoreDownloadTask();
    }

    File settingsFile() {
        return new File(ctx.homeDir, "settings.dat");
    }

    File resumeTorrentFile(String infoHash) {
        return new File(ctx.homeDir, infoHash + ".torrent");
    }

    File resumeDataFile(String infoHash) {
        return new File(ctx.homeDir, infoHash + ".resume");
    }

    File readTorrentPath(String infoHash) {
        File torrent = null;

        try {
            byte[] arr = FileUtils.readFileToByteArray(resumeTorrentFile(infoHash));
            entry e = entry.bdecode(Vectors.bytes2byte_vector(arr));
            torrent = new File(e.dict().get(TORRENT_ORIG_PATH_KEY).string());
        } catch (Throwable e) {
            // can't recover original torrent path
        }

        return torrent;
    }

    File readSavePath(String infoHash) {
        File savePath = null;

        try {
            byte[] arr = FileUtils.readFileToByteArray(resumeDataFile(infoHash));
            entry e = entry.bdecode(Vectors.bytes2byte_vector(arr));
            savePath = new File(e.dict().get("save_path").string());
        } catch (Throwable e) {
            // can't recover original torrent path
        }

        return savePath;
    }

    private File saveTorrent(TorrentInfo ti) {
        File torrentFile;

        try {
            String name = ti.name();
            if (name == null || name.length() == 0) {
                name = ti.infoHash().toString();
            }
            name = escapeFilename(name);

            torrentFile = new File(ctx.torrentsDir, name + ".torrent");
            byte[] arr = ti.toEntry().bencode();

            FileSystem fs = Platforms.get().fileSystem();
            fs.write(torrentFile, arr);
            fs.scan(torrentFile);
        } catch (Throwable e) {
            torrentFile = null;
            LOGGER.warn("Error saving torrent info to file", e);
        }

        return torrentFile;
    }

    private void saveResumeTorrent(File torrent) {
        try {
            TorrentInfo ti = new TorrentInfo(torrent);
            entry e = ti.toEntry().swig();
            e.dict().set(TORRENT_ORIG_PATH_KEY, new entry(torrent.getAbsolutePath()));
            byte[] arr = Vectors.byte_vector2bytes(e.bencode());
            FileUtils.writeByteArrayToFile(resumeTorrentFile(ti.infoHash().toString()), arr);
        } catch (Throwable e) {
            LOGGER.warn("Error saving resume torrent", e);
        }
    }

    private void doResumeData(TorrentAlert<?> alert, boolean force) {
        try {
            if (!force) {
                // TODO: I need to restore this later
                if (ctx.optimizeMemory) {
                    return;
                }
            }
            TorrentHandle th = session.findTorrent(alert.handle().getInfoHash());
            if (th != null && th.isValid()) {
                th.saveResumeData();
            }
        } catch (Throwable e) {
            LOGGER.warn("Error triggering resume data", e);
        }
    }

    private void fireStarted() {
        if (listener != null) {
            listener.started(this);
        }
    }

    private void fireStopped() {
        if (listener != null) {
            listener.stopped(this);
        }
    }

    private void fireDownloadAdded(TorrentAlert<?> alert) {
        try {
            TorrentHandle th = session.findTorrent(alert.handle().getInfoHash());
            BTDownload dl = new BTDownload(this, th);
            if (listener != null) {
                listener.downloadAdded(this, dl);
            }
        } catch (Throwable e) {
            LOGGER.error("Unable to create and/or notify the new download", e);
        }
    }

    private void fireDownloadUpdate(TorrentHandle th) {
        try {
            BTDownload dl = new BTDownload(this, th);
            if (listener != null) {
                listener.downloadUpdate(this, dl);
            }
        } catch (Throwable e) {
            LOGGER.error("Unable to notify update the a download", e);
        }
    }

    private void onListenSucceeded(ListenSucceededAlert alert) {
        try {
            TcpEndpoint endp = alert.getEndpoint();
            if (alert.getSocketType() == ListenSucceededAlert.SocketType.TCP) {
                String address = endp.address().toString();
                int port = endp.port();
                listenEndpoints.add(new TcpEndpoint(address, port));
            }

            String s = "endpoint: " + endp + " type:" + alert.getSocketType();
            LOGGER.info("Listen succeeded on " + s);
        } catch (Throwable e) {
            LOGGER.error("Error adding listen endpoint to internal list", e);
        }
    }

    private void onListenFailed(ListenFailedAlert alert) {
        TcpEndpoint endp = alert.endpoint();
        String s = "endpoint: " + endp + " type:" + alert.getSocketType();
        String message = alert.getError().message();
        LOGGER.info("Listen failed on " + s + " (error: " + message + ")");
    }

    private void migrateVuzeDownloads() {
        try {
            File dir = new File(ctx.homeDir.getParent(), "azureus");
            File file = new File(dir, "downloads.config");

            if (file.exists()) {
                Entry configEntry = Entry.bdecode(file);
                List<Entry> downloads = configEntry.dictionary().get("downloads").list();

                for (Entry d : downloads) {
                    try {
                        Map<String, Entry> map = d.dictionary();
                        File saveDir = new File(map.get("save_dir").string());
                        File torrent = new File(map.get("torrent").string());
                        ArrayList<Entry> filePriorities = map.get("file_priorities").list();

                        Priority[] priorities = Priority.array(Priority.IGNORE, filePriorities.size());
                        for (int i = 0; i < filePriorities.size(); i++) {
                            long p = filePriorities.get(i).integer();
                            if (p != 0) {
                                priorities[i] = Priority.NORMAL;
                            }
                        }

                        if (torrent.exists() && saveDir.exists()) {
                            LOGGER.info("Restored old vuze download: " + torrent);
                            restoreDownloadsQueue.add(new RestoreDownloadTask(torrent, saveDir, priorities, null));
                            saveResumeTorrent(torrent);
                        }
                    } catch (Throwable e) {
                        LOGGER.error("Error restoring vuze torrent download", e);
                    }
                }

                file.delete();
            }
        } catch (Throwable e) {
            LOGGER.error("Error migrating old vuze downloads", e);
        }
    }

    private File setupSaveDir(File saveDir) {
        File result = null;

        if (saveDir == null) {
            if (ctx.dataDir != null) {
                result = ctx.dataDir;
            } else {
                LOGGER.warn("Unable to setup save dir path, review your logic, both saveDir and ctx.dataDir are null.");
            }
        } else {
            result = saveDir;
        }

        FileSystem fs = Platforms.get().fileSystem();

        if (result != null && !fs.isDirectory(result) && !fs.mkdirs(result)) {
            result = null;
            LOGGER.warn("Failed to create save dir to download");
        }

        if (result != null && !fs.canWrite(result)) {
            result = null;
            LOGGER.warn("Failed to setup save dir with write access");
        }

        return result;
    }

    private void runNextRestoreDownloadTask() {
        final RestoreDownloadTask task;
        try {
            task = restoreDownloadsQueue.poll();
        } catch (Throwable t) {
            // on Android, LinkedList's .poll() implementation throws a NoSuchElementException
            return;
        }
        if (task != null) {
            task.run();
        }
    }

    public void download(TorrentInfo ti, File saveDir, Priority[] priorities, File resumeFile, String magnetUrlParams) {

        TorrentHandle th = session.findTorrent(ti.infoHash());

        if (th != null) {
            // found a download with the same hash, just adjust the priorities if needed
            if (priorities != null) {
                if (ti.numFiles() != priorities.length) {
                    throw new IllegalArgumentException("The priorities length should be equals to the number of files");
                }

                th.prioritizeFiles(priorities);
                fireDownloadUpdate(th);
                th.resume();
            } else {
                // did they just add the entire torrent (therefore not selecting any priorities)
                final Priority[] wholeTorrentPriorities = Priority.array(Priority.NORMAL, ti.numFiles());
                th.prioritizeFiles(wholeTorrentPriorities);
                fireDownloadUpdate(th);
                th.resume();
            }
        } else { // new download
            addTorrentSupport(ti, saveDir, priorities, resumeFile, true, magnetUrlParams);
            //session.asyncAddTorrent(ti, saveDir, priorities, resumeFile);
        }
    }

    private TorrentHandle addTorrentSupport(TorrentInfo ti, File saveDir, Priority[] priorities, File resumeFile, boolean async, String magnetUrlParams) {

        String savePath = null;
        if (saveDir != null) {
            savePath = saveDir.getAbsolutePath();
        } else if (resumeFile == null) {
            throw new IllegalArgumentException("Both saveDir and resumeFile can't be null at the same time");
        }

        add_torrent_params p = add_torrent_params.create_instance();

        if (magnetUrlParams != null) {
            p.setUrl(magnetUrlParams);
        }

        p.set_ti(ti.swig());
        if (savePath != null) {
            p.setSave_path(savePath);
        }

        if (priorities != null) {
            byte_vector v = new byte_vector();
            for (int i = 0; i < priorities.length; i++) {
                v.push_back((byte) priorities[i].swig());
            }
            p.set_file_priorities(v);
        }
        p.setStorage_mode(storage_mode_t.storage_mode_sparse);

        long flags = p.get_flags();

        flags &= ~add_torrent_params.flags_t.flag_auto_managed.swigValue();

        if (resumeFile != null) {
            try {
                byte[] data = FileUtils.readFileToByteArray(resumeFile);
                p.set_resume_data(Vectors.bytes2byte_vector(data));

                flags |= add_torrent_params.flags_t.flag_use_resume_save_path.swigValue();
            } catch (Throwable e) {
                LOGGER.warn("Unable to set resume data", e);
            }
        }

        p.set_flags(flags);

        if (async) {
            session.swig().async_add_torrent(p);
            return null;
        } else {
            error_code ec = new error_code();
            torrent_handle th = session.swig().add_torrent(p, ec);
            return new TorrentHandle(th);
        }
    }

    // this is here until we have a properly done OS utils.
    private static String escapeFilename(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|\\[\\]]+", "_");
    }

    private final class InnerListener implements AlertListener {
        @Override
        public int[] types() {
            return INNER_LISTENER_TYPES;
        }

        @Override
        public void alert(Alert<?> alert) {

            AlertType type = alert.type();

            switch (type) {
                case TORRENT_ADDED:
                    TorrentAlert<?> torrentAlert = (TorrentAlert<?>) alert;
                    fireDownloadAdded(torrentAlert);
                    runNextRestoreDownloadTask();
                    break;
                case PIECE_FINISHED:
                    doResumeData((TorrentAlert<?>) alert, false);
                    break;
                case PORTMAP:
                    firewalled = false;
                    break;
                case PORTMAP_ERROR:
                    firewalled = true;
                    break;
                case DHT_STATS:
                    totalDHTNodes = (int) session.getStats().dhtNodes();
                    break;
                case STORAGE_MOVED:
                    doResumeData((TorrentAlert<?>) alert, true);
                    break;
                case LISTEN_SUCCEEDED:
                    onListenSucceeded((ListenSucceededAlert) alert);
                    break;
                case LISTEN_FAILED:
                    onListenFailed((ListenFailedAlert) alert);
                    break;
                case EXTERNAL_IP:
                    onExternalIpAlert((ExternalIpAlert) alert);
                    break;
                case METADATA_RECEIVED:
                    saveMagnetData((MetadataReceivedAlert) alert);
                    break;
            }
        }
    }

    private final class RestoreDownloadTask implements Runnable {

        private final File torrent;
        private final File saveDir;
        private final Priority[] priorities;
        private final File resume;

        public RestoreDownloadTask(File torrent, File saveDir, Priority[] priorities, File resume) {
            this.torrent = torrent;
            this.saveDir = saveDir;
            this.priorities = priorities;
            this.resume = resume;
        }

        @Override
        public void run() {
            try {
                session.asyncAddTorrent(new TorrentInfo(torrent), saveDir, priorities, resume);
            } catch (Throwable e) {
                LOGGER.error("Unable to restore download from previous session. (" + torrent.getAbsolutePath() + ")", e);
            }
        }
    }

    //--------------------------------------------------
    // Settings methods
    //--------------------------------------------------

    public int getDownloadSpeedLimit() {
        if (session == null) {
            return 0;
        }
        return session.getSettingsPack().downloadRateLimit();
    }

    public void setDownloadSpeedLimit(int limit) {
        if (session == null) {
            return;
        }
        SettingsPack settingsPack = session.getSettingsPack();
        settingsPack.setDownloadRateLimit(limit);
        saveSettings(settingsPack);
    }

    public int getUploadSpeedLimit() {
        if (session == null) {
            return 0;
        }
        return session.getSettingsPack().uploadRateLimit();
    }

    public void setUploadSpeedLimit(int limit) {
        if (session == null) {
            return;
        }
        SettingsPack settingsPack = session.getSettingsPack();
        settingsPack.setUploadRateLimit(limit);
        session.applySettings(settingsPack);
        saveSettings(settingsPack);
    }

    public int getMaxActiveDownloads() {
        if (session == null) {
            return 0;
        }
        return session.getSettingsPack().activeDownloads();
    }

    public void setMaxActiveDownloads(int limit) {
        if (session == null) {
            return;
        }
        SettingsPack settingsPack = session.getSettingsPack();
        settingsPack.activeDownloads(limit);
        session.applySettings(settingsPack);
        saveSettings(settingsPack);
    }

    public int getMaxActiveSeeds() {
        if (session == null) {
            return 0;
        }
        return session.getSettingsPack().activeSeeds();
    }

    public void setMaxActiveSeeds(int limit) {
        if (session == null) {
            return;
        }
        SettingsPack settingsPack = session.getSettingsPack();
        settingsPack.activeSeeds(limit);
        session.applySettings(settingsPack);
        saveSettings(settingsPack);
    }

    public int getMaxConnections() {
        if (session == null) {
            return 0;
        }
        return session.getSettingsPack().connectionsLimit();
    }

    public void setMaxConnections(int limit) {
        if (session == null) {
            return;
        }
        SettingsPack settingsPack = session.getSettingsPack();
        settingsPack.setConnectionsLimit(limit);
        session.applySettings(settingsPack);
        saveSettings(settingsPack);
    }

    public int getMaxPeers() {
        if (session == null) {
            return 0;
        }
        return session.getSettingsPack().maxPeerlistSize();
    }

    public void setMaxPeers(int limit) {
        if (session == null) {
            return;
        }
        SettingsPack settingsPack = session.getSettingsPack();
        settingsPack.setMaxPeerlistSize(limit);
        session.applySettings(settingsPack);
        saveSettings(settingsPack);
    }

    public String getListenInterfaces() {
        if (session == null) {
            return null;
        }
        return session.getSettingsPack().getString(settings_pack.string_types.listen_interfaces.swigValue());
    }

    public void setListenInterfaces(String value) {
        super.listenInterfaces(value);
        if (session == null) {
            return;
        }
        SettingsPack sp = new SettingsPack();
        sp.setString(settings_pack.string_types.listen_interfaces.swigValue(), value);
        saveSettings(sp);
    }

    public int getTotalDHTNodes() {
        return (int) dhtNodes();
    }
}
