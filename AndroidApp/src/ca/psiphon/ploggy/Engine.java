/*
 * Copyright (c) 2013, Psiphon Inc.
 * All rights reserved.
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
 *
 */

package ca.psiphon.ploggy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.preference.PreferenceManager;

import com.squareup.otto.Subscribe;

/**
 * Coordinator for background Ploggy work.
 * 
 * The Engine:
 * - schedule friend status push/pulls
 * - maintains a worker thread pool for background tasks (pushing/pulling
 *   friends and handling friend requests
 * - runs the local location monitor
 * - (re)-starts and stops the local web server and Tor Hidden Service to
 *   handle requests from friends
 *   
 * An Engine instance is intended to be run via an Android Service set to
 * foreground mode (i.e., long running).
 */
public class Engine implements OnSharedPreferenceChangeListener, WebServer.RequestHandler {
    
    private static final String LOG_TAG = "Engine";

    private Context mContext;
    private SharedPreferences mSharedPreferences;
    private ScheduledExecutorService mTaskThreadPool;
    private HashMap<String, ScheduledFuture<?>> mFriendPullTasks;
    private LocationMonitor mLocationMonitor;
    private WebServer mWebServer;
    private TorWrapper mTorWrapper;
    
    private static final int THREAD_POOL_SIZE = 30;

    public Engine(Context context) {
        Utils.initSecureRandom();
        mContext = context;

        // TODO: distinct instance of preferences for each persona
        // e.g., getSharedPreferencesName("persona1");
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    public synchronized void start() throws Utils.ApplicationError {
        Events.register(this);
        mTaskThreadPool = Executors.newScheduledThreadPool(THREAD_POOL_SIZE);
        mFriendPullTasks = new HashMap<String, ScheduledFuture<?>>();
        mLocationMonitor = new LocationMonitor(this);
        mLocationMonitor.start();
        startSharingService();
        schedulePullFriends();
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    public synchronized void stop() {
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
        Events.unregister(this);
        stopSharingService();
        if (mLocationMonitor != null) {
        	mLocationMonitor.stop();
        	mLocationMonitor = null;
        }
        if (mTaskThreadPool != null) {
            Utils.shutdownExecutorService(mTaskThreadPool);
	        mTaskThreadPool = null;
	        mFriendPullTasks = null;
        }
    }

    @Override
    public synchronized void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        try {
            stop();
            start();
        } catch (Utils.ApplicationError e) {
            // TODO: log?
        }
    }

    public synchronized void submitTask(Runnable task) {
        mTaskThreadPool.submit(task);
    }
    
    private void startSharingService() throws Utils.ApplicationError {
        try {
            Data.Self self = Data.getInstance().getSelf();
            ArrayList<String> friendCertificates = new ArrayList<String>();
            for (Data.Friend friend : Data.getInstance().getFriends()) {
                friendCertificates.add(friend.mPublicIdentity.mX509Certificate);
            }
            stopSharingService();
            mWebServer = new WebServer(
                    this,
                    new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                    friendCertificates);
            mWebServer.start();
            mTorWrapper = new TorWrapper(
                    TorWrapper.Mode.MODE_RUN_SERVICES,
                    new HiddenService.KeyMaterial(self.mPublicIdentity.mHiddenServiceHostname, self.mPrivateIdentity.mHiddenServicePrivateKey),
                    mWebServer.getListeningPort());
            // TODO: poll mTorWrapper.awaitStarted() to check for errors and retry... 
            mTorWrapper.start();
        } catch (IOException e) {
            throw new Utils.ApplicationError(LOG_TAG, e);
        }
    }
    
    private void stopSharingService() {
    	if (mTorWrapper != null) {
    		mTorWrapper.stop();
    	}
    	if (mWebServer != null) {
    		mWebServer.stop();
    	}
    }
    
    public synchronized int getTorSocksProxyPort() throws Utils.ApplicationError {
        if (mTorWrapper != null) {
            return mTorWrapper.getSocksProxyPort();
        }
        throw new Utils.ApplicationError(LOG_TAG, "no Tor socks proxy");
    }
    
    @Subscribe
    public synchronized void onUpdatedSelf(Events.UpdatedSelf updatedSelf) {
        // Apply new transport and hidden service credentials
        try {
            startSharingService();
        } catch (Utils.ApplicationError e) {
            // TODO: log?
        }                        
    }

    @Subscribe
    public synchronized void onNewSelfLocation(Events.NewSelfLocation newSelfLocation) {
        // TODO: location fix timestamp vs. status update timestamp?
        // TODO: apply precision factor to long/lat/address
        // TODO: factor Location.getAccuracy() into precision?
        try {
            Data.getInstance().updateSelfStatus(
                    new Data.Status(
                            Utils.getCurrentTimestamp(),
                            newSelfLocation.mLocation.getLongitude(),
                            newSelfLocation.mLocation.getLatitude(),
                            getIntPreference(R.string.preferenceLimitLocationPrecision),
                            newSelfLocation.mAddress.getCountryName()));
        } catch (Utils.ApplicationError e) {
            // TODO: log
        }
    }
    
    @Subscribe
    public synchronized void onNewSelfStatus(Events.UpdatedSelfStatus updatedSelfStatus) {
        try {
            // Immediately push new status to all friends. If this fails for any reason,
            // implicitly fall back to friends pulling status.
            pushToFriends();
        } catch (Utils.ApplicationError e) {
            // TODO: log
        }
    }
    
    @Subscribe
    public synchronized void onUpdatedFriend(Events.UpdatedFriend updatedFriend) {
        schedulePullFriend(updatedFriend.mId);
    }
    
    private void pushToFriends() throws Utils.ApplicationError {
        // TODO: check for existing pushes in worker thread queue
        for (Data.Friend friend : Data.getInstance().getFriends()) {
            final String taskFriendId = friend.mId;
            Runnable task = new Runnable() {
                public void run() {
                    try {
                        Data data = Data.getInstance();
                        Data.Self self = data.getSelf();
                        Data.Status selfStatus = data.getSelfStatus();
                        Data.Friend friend = data.getFriendById(taskFriendId);
                        WebClient.makePostRequest(
                                new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                                friend.mPublicIdentity.mX509Certificate,
                                getTorSocksProxyPort(),
                                friend.mPublicIdentity.mHiddenServiceHostname,
                                Protocol.WEB_SERVER_VIRTUAL_PORT,
                                Protocol.PUSH_STATUS_REQUEST_PATH,
                                Json.toJson(selfStatus));
                        data.updateFriendLastSentStatusTimestamp(taskFriendId);
                    } catch (Data.DataNotFoundException e) {
                        // TODO: ...Deleted; Next pull won't be scheduled
                    } catch (Utils.ApplicationError e) {
                        // TODO: ...?
                    }
                }
            };
            submitTask(task);
        }
    }
    
    private void schedulePullFriend(String friendId) {
        final String taskFriendId = friendId;
        Runnable task = new Runnable() {
            public void run() {
                try {
                    Data data = Data.getInstance();
                    Data.Self self = data.getSelf();
                    Data.Friend friend = data.getFriendById(taskFriendId);
                    String response = WebClient.makeGetRequest(
                            new X509.KeyMaterial(self.mPublicIdentity.mX509Certificate, self.mPrivateIdentity.mX509PrivateKey),
                            friend.mPublicIdentity.mX509Certificate,
                            getTorSocksProxyPort(),
                            friend.mPublicIdentity.mHiddenServiceHostname,
                            Protocol.WEB_SERVER_VIRTUAL_PORT,
                            Protocol.PULL_STATUS_REQUEST_PATH);
                    Data.Status friendStatus = Json.fromJson(response, Data.Status.class);
                    data.updateFriendStatus(taskFriendId, friendStatus);
                    data.updateFriendLastReceivedStatusTimestamp(taskFriendId);
                } catch (Data.DataNotFoundException e) {
                    // TODO: ...Deleted; Next pull won't be scheduled
                } catch (Utils.ApplicationError e) {
                    // TODO: ...?
                }
            }
        };
        // Cancel any existing pull schedule for this friend
        if (mFriendPullTasks.containsKey(taskFriendId)) {
            mFriendPullTasks.get(taskFriendId).cancel(false);
        }
        ScheduledFuture<?> future = mTaskThreadPool.scheduleAtFixedRate(
                task, 0, Protocol.PULL_PERIOD_IN_MILLISECONDS, TimeUnit.MILLISECONDS);
        mFriendPullTasks.put(taskFriendId, future);
    }

    private void schedulePullFriends() throws Utils.ApplicationError {
        for (Data.Friend friend : Data.getInstance().getFriends()) {
            schedulePullFriend(friend.mId);
        }
    }
    
    public synchronized Data.Status handlePullStatusRequest(String friendCertificate) throws Utils.ApplicationError {
        // Friend is requesting (pulling) self status
        // TODO: cancel any pending push to this friend?
        Data data = Data.getInstance();
        Data.Friend friend = data.getFriendByCertificate(friendCertificate);
        Data.Status status = data.getSelfStatus();
        // TODO: we don't yet know the friend really received the response bytes
        data.updateFriendLastSentStatusTimestamp(friend.mId);
        return status;        
    }
    
    public synchronized void handlePushStatusRequest(String friendCertificate, Data.Status status) throws Utils.ApplicationError  {
        // Friend is pushing their own status
        Data data = Data.getInstance();
        Data.Friend friend = data.getFriendByCertificate(friendCertificate);
        data.updateFriendStatus(friend.mId, status);
        // TODO: we don't yet know the friend really received the response bytes
        data.updateFriendLastReceivedStatusTimestamp(friend.mId);        
        // Reschedule (delay) any outstanding pull from this friend
        schedulePullFriend(friend.mId);
    }
    
    public synchronized Context getContext() {
        return mContext;
    }
    
    public synchronized boolean getBooleanPreference(int keyResID) throws Utils.ApplicationError {
        String key = mContext.getString(keyResID);
        if (!mSharedPreferences.contains(key)) {
            throw new Utils.ApplicationError(LOG_TAG, "missing preference default: " + key);
        }
        return mSharedPreferences.getBoolean(key, false);        
    }
    
    public synchronized int getIntPreference(int keyResID) throws Utils.ApplicationError {
        String key = mContext.getString(keyResID);
        if (!mSharedPreferences.contains(key)) {
            throw new Utils.ApplicationError(LOG_TAG, "missing preference default: " + key);
        }
        return mSharedPreferences.getInt(key, 0);        
    }
}