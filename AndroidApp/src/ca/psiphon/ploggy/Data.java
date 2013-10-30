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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import android.content.Context;

/**
 * Data persistence for self, friends, and status.
 *
 * On disk, data is represented as JSON stored in individual files. In memory, data is represented
 * as immutable POJOs which are thread-safe and easily serializable. Self and friend metadata, including
 * identity, and recent status data are kept in-memory. Large data such as map tiles will be left on
 * disk with perhaps an in-memory cache.
 * 
 * Simple consistency is provided: data changes are first written to a commit file, then the commit
 * file replaces the data file. In memory structures are replaced only after the file write succeeds.
 * 
 * If local security is added to the scope of Ploggy, here's where we'd interface with SQLCipher and/or
 * KeyChain, etc.
 */
public class Data {
    
    private static final String LOG_TAG = "Data";
    
    public static class Self {
        public final Identity.PublicIdentity mPublicIdentity;
        public final Identity.PrivateIdentity mPrivateIdentity;

        public Self(
                Identity.PublicIdentity publicIdentity,
                Identity.PrivateIdentity privateIdentity) {
            mPublicIdentity = publicIdentity;
            mPrivateIdentity = privateIdentity;
        }
    }
    
    public static class Friend {
        public final String mId;
        public final Identity.PublicIdentity mPublicIdentity;
        public final String mLastSentStatusTimestamp;
        public final String mLastReceivedStatusTimestamp;

        public Friend(
                Identity.PublicIdentity publicIdentity) throws Utils.ApplicationError {
            this(publicIdentity, "", "");
        }
        public Friend(
                Identity.PublicIdentity publicIdentity,
                String lastSentStatusTimestamp,
                String lastReceivedStatusTimestamp) throws Utils.ApplicationError {
            mId = Utils.encodeHex(publicIdentity.getFingerprint());
            mPublicIdentity = publicIdentity;
            mLastSentStatusTimestamp = lastSentStatusTimestamp;
            mLastReceivedStatusTimestamp = lastReceivedStatusTimestamp;
        }
    }
    
    public static class Status {
        public final String mTimestamp;
        public final double mLongitude;
        public final double mLatitude;
        public final int mPrecision;
        public final String mStreetAddress;
        // TODO: public final ArrayList<String> mMapTileIds;
        // TODO: public final String mMessage;
        // TODO: public final String mPhotoId;

        public Status(
                String timestamp,
                double longitude,
                double latitude,
                int precision,
                String streetAddress) {
            mTimestamp = timestamp;
            mLongitude = longitude;
            mLatitude = latitude;
            mPrecision = precision;
            mStreetAddress = streetAddress;            
        }
    }
    
    public static class DataNotFoundException extends Utils.ApplicationError {
        private static final long serialVersionUID = -8736069103392081076L;
        
        public DataNotFoundException() {
            super(LOG_TAG, "data not found");
        }
    }

    // ---- Singleton ----
    private static Data instance = null;
    public static synchronized Data getInstance() {
       if(instance == null) {
          instance = new Data();
       }
       return instance;
    }
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException();
    }
    // -------------------

    // TODO: SQLCipher/IOCipher storage? key/value store?
    // TODO: use http://nelenkov.blogspot.ca/2011/11/using-ics-keychain-api.html?
    // ...consistency: write file, then update in-memory; 2pc; only for short lists of friends
    // ...eventually use file system for map tiles etc.
       
    private static final String DATA_DIRECTORY = "ploggyData"; 
    private static final String SELF_FILENAME = "self.json"; 
    private static final String SELF_STATUS_FILENAME = "selfStatus.json"; 
    private static final String FRIENDS_FILENAME = "friends.json"; 
    private static final String FRIEND_STATUS_FILENAME_FORMAT_STRING = "%s-friendStatus.json"; 
    private static final String COMMIT_FILENAME_SUFFIX = ".commit"; 
    
    Self mSelf;
    Status mSelfStatus;
    List<Friend> mFriends;
    HashMap<String, Status> mFriendStatuses;

    public synchronized void reset() throws Utils.ApplicationError {
        // Warning: deletes all files in DATA_DIRECTORY (not recursively)
        File directory = Utils.getApplicationContext().getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
        directory.mkdirs();
        boolean deleteFailed = false;
        for (String child : directory.list()) {
            File file = new File(directory, child);
            if (file.isFile()) {
                if (!file.delete()) {
                    deleteFailed = true;
                    // Keep attempting to delete remaining files...
                }
            }
        }
        if (deleteFailed) {
            throw new Utils.ApplicationError(LOG_TAG, "delete data file failed");
        }
    }
    
    public synchronized Self getSelf() throws Utils.ApplicationError, DataNotFoundException {
    	if (mSelf == null) {
            mSelf = Json.fromJson(readFile(SELF_FILENAME), Self.class);
        }
        return mSelf;
    }

    public synchronized void updateSelf(Self self) throws Utils.ApplicationError {
        writeFile(SELF_FILENAME, Json.toJson(self));
        mSelf = self;
        // TODO: string resource; log self fingerprint
        Log.addEntry(LOG_TAG, "updated self");
        Events.post(new Events.UpdatedSelf());
    }

    public synchronized Status getSelfStatus() throws Utils.ApplicationError, DataNotFoundException {
        if (mSelfStatus == null) {
            mSelfStatus = Json.fromJson(readFile(SELF_STATUS_FILENAME), Status.class);
        }
        return mSelfStatus;
    }

    public synchronized void updateSelfStatus(Data.Status status) throws Utils.ApplicationError {
        writeFile(SELF_STATUS_FILENAME, Json.toJson(status));
        mSelfStatus = status;
        // TODO: string resource
        Log.addEntry(LOG_TAG, "updated self status");
        Events.post(new Events.UpdatedSelfStatus());
    }

    private void loadFriends() throws Utils.ApplicationError {
        if (mFriends == null) {
	    	try {
				mFriends = Json.fromJsonArray(readFile(FRIENDS_FILENAME), Friend.class);
			} catch (DataNotFoundException e) {
				mFriends = new ArrayList<Friend>();
			}
	    	mFriends = new ArrayList<Friend>();
        }
    }
    
    public synchronized final ArrayList<Friend> getFriends() throws Utils.ApplicationError {
    	loadFriends();
        return new ArrayList<Friend>(mFriends);
    }

    public synchronized Friend getFriendById(String id) throws Utils.ApplicationError, DataNotFoundException {
        loadFriends();
        synchronized(mFriends) {
            for (Friend friend : mFriends) {
                if (friend.mId.equals(id)) {
                    return friend;
                }
            }
        }
        throw new DataNotFoundException();
    }

    public synchronized Friend getFriendByCertificate(String certificate) throws Utils.ApplicationError, DataNotFoundException {
        loadFriends();
        synchronized(mFriends) {
            for (Friend friend : mFriends) {
                if (friend.mPublicIdentity.mX509Certificate.equals(certificate)) {
                    return friend;
                }
            }
        }
        throw new DataNotFoundException();
    }

    private void insertOrUpdate(Friend friend, List<Friend> list) {
    	boolean found = false;
        for (int i = 0; i < list.size(); i++) {
        	if (list.get(i).mId.equals(friend.mId)) {
        		list.set(i, friend);
        		found = true;
        		break;
        	}
        }
        if (!found) {
        	list.add(friend);
        }
    }

    public synchronized void insertOrUpdateFriend(Friend friend) throws Utils.ApplicationError {
    	loadFriends();
    	synchronized(mFriends) {
	    	ArrayList<Friend> newFriends = new ArrayList<Friend>(mFriends);
	    	insertOrUpdate(friend, newFriends);
	        writeFile(FRIENDS_FILENAME, Json.toJson(newFriends));
	    	insertOrUpdate(friend, mFriends);
            // TODO: string resource; log friend nickname
	        Log.addEntry(LOG_TAG, "updated friend");
	 	    Events.post(new Events.UpdatedFriend(friend.mId));
    	}
    }

    public synchronized Date getFriendLastSentStatusTimestamp(String friendId) throws Utils.ApplicationError {
        Friend friend = getFriendById(friendId);
        return Utils.parseISO8601Date(friend.mLastSentStatusTimestamp);
    }
    
    public synchronized void updateFriendLastSentStatusTimestamp(String friendId) throws Utils.ApplicationError {
        // TODO: don't write an entire file for each timestamp update!
        Friend friend = getFriendById(friendId);
        insertOrUpdateFriend(new Friend(friend.mPublicIdentity, Utils.getCurrentTimestamp(), friend.mLastReceivedStatusTimestamp));
    }
    
    public synchronized Date getFriendLastReceivedStatusTimestamp(String friendId) throws Utils.ApplicationError {
        Friend friend = getFriendById(friendId);
        return Utils.parseISO8601Date(friend.mLastReceivedStatusTimestamp);
    }
    
    public synchronized void updateFriendLastReceivedStatusTimestamp(String friendId) throws Utils.ApplicationError {
        // TODO: don't write an entire file for each timestamp update!
        Friend friend = getFriendById(friendId);
        insertOrUpdateFriend(new Friend(friend.mPublicIdentity, friend.mLastSentStatusTimestamp, Utils.getCurrentTimestamp()));
    }
    
    private void removeFriendHelper(String id, List<Friend> list) throws DataNotFoundException {
    	boolean found = false;
        for (int i = 0; i < list.size(); i++) {
        	if (list.get(i).mId.equals(id)) {
        		list.remove(i);
        		found = true;
        		break;
        	}
        }
        if (!found) {
        	throw new DataNotFoundException();
        }
    }

    public synchronized void removeFriend(String id) throws Utils.ApplicationError, DataNotFoundException {
    	loadFriends();
    	synchronized(mFriends) {
	    	ArrayList<Friend> newFriends = new ArrayList<Friend>(mFriends);
	    	removeFriendHelper(id, newFriends);
	        writeFile(FRIENDS_FILENAME, Json.toJson(newFriends));
	        removeFriendHelper(id, mFriends);
            // TODO: string resource; log friend nickname
            Log.addEntry(LOG_TAG, "removed friend");
            Events.post(new Events.RemovedFriend(id));
    	}
    }

    public synchronized Status getFriendStatus(String id) throws Utils.ApplicationError, DataNotFoundException {
    	String filename = String.format(FRIEND_STATUS_FILENAME_FORMAT_STRING, id);
        return Json.fromJson(readFile(filename), Status.class);
    }

    public synchronized void updateFriendStatus(String id, Status status) throws Utils.ApplicationError {
    	String filename = String.format(FRIEND_STATUS_FILENAME_FORMAT_STRING, id);
    	writeFile(filename, Json.toJson(status));
        // TODO: string resource; log friend nickname
        Log.addEntry(LOG_TAG, "updated friend status");
        Events.post(new Events.UpdatedFriendStatus(id));
    }

    private static String readFile(String filename) throws Utils.ApplicationError, DataNotFoundException {
        FileInputStream inputStream = null;
        try {
            File directory = Utils.getApplicationContext().getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
        	String commitFilename = filename + COMMIT_FILENAME_SUFFIX;
            File commitFile = new File(directory, commitFilename);
            File file = new File(directory, filename);
        	replaceFileIfExists(commitFile, file);
            inputStream = new FileInputStream(file);
            return Utils.readInputStreamToString(inputStream);
        } catch (FileNotFoundException e) {
            throw new DataNotFoundException();
        } catch (IOException e) {
            throw new Utils.ApplicationError(LOG_TAG, e);
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                }
            }
        }        
    }

    private static void writeFile(String filename, String value) throws Utils.ApplicationError {
        FileOutputStream outputStream = null;
        try {
            File directory = Utils.getApplicationContext().getDir(DATA_DIRECTORY, Context.MODE_PRIVATE);
        	String commitFilename = filename + COMMIT_FILENAME_SUFFIX;
            File commitFile = new File(directory, commitFilename);
            File file = new File(directory, filename);
            outputStream = new FileOutputStream(commitFile);
            outputStream.write(value.getBytes());
            outputStream.close();
            replaceFileIfExists(commitFile, file);
        } catch (IOException e) {
            throw new Utils.ApplicationError(LOG_TAG, e);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private static void replaceFileIfExists(File commitFile, File file) throws IOException {
        if (commitFile.exists()) {
	        file.delete();
	        commitFile.renameTo(file);
        }
    }
}