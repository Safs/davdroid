/*******************************************************************************
 * Copyright (c) 2014 Richard Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Richard Hirner (bitfire web engineering) - initial API and implementation
 ******************************************************************************/
package at.bitfire.davdroid.syncadapter;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import org.apache.http.HttpStatus;

import ch.boye.httpclientandroidlib.impl.client.CloseableHttpClient;
import lombok.Getter;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SyncResult;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import at.bitfire.davdroid.resource.LocalCollection;
import at.bitfire.davdroid.resource.LocalStorageException;
import at.bitfire.davdroid.resource.RemoteCollection;
import at.bitfire.davdroid.webdav.DavException;
import at.bitfire.davdroid.webdav.DavHttpClient;
import at.bitfire.davdroid.webdav.HttpException;

public abstract class DavSyncAdapter extends AbstractThreadedSyncAdapter implements Closeable {
	private final static String TAG = "davdroid.DavSyncAdapter";
	
	@Getter private static String androidID;
	
	protected AccountManager accountManager;
	protected CloseableHttpClient httpClient;

	
	public DavSyncAdapter(Context context) {
		super(context, true);
		
		synchronized(this) {
			if (androidID == null)
				androidID = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
		}
		
		accountManager = AccountManager.get(context);
		httpClient = DavHttpClient.create();
	}
	
	@Override public void close() {
		// apparently may be called from a GUI thread
		new AsyncTask<Void, Void, Void>() {
			@Override
			protected Void doInBackground(Void... params) {
				try {
					httpClient.close();
					httpClient = null;
				} catch (IOException e) {
					Log.w(TAG, "Couldn't close HTTP client", e);
				}
				return null;
			}
		}.execute();
	}
	
	
	protected abstract Map<LocalCollection<?>, RemoteCollection<?>> getSyncPairs(Account account, ContentProviderClient provider);
	

	@Override
	public void onPerformSync(Account account, Bundle extras, String authority,	ContentProviderClient provider, SyncResult syncResult) {
		Log.i(TAG, "Performing sync for authority " + authority);
		
		// set class loader for iCal4j ResourceLoader
		Thread.currentThread().setContextClassLoader(getContext().getClassLoader());
		
		Map<LocalCollection<?>, RemoteCollection<?>> syncCollections = getSyncPairs(account, provider);
		if (syncCollections == null)
			Log.i(TAG, "Nothing to synchronize");
		else
			try {
				for (Map.Entry<LocalCollection<?>, RemoteCollection<?>> entry : syncCollections.entrySet())
					new SyncManager(entry.getKey(), entry.getValue()).synchronize(extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL), syncResult);
				
			} catch (DavException ex) {
				syncResult.stats.numParseExceptions++;
				Log.e(TAG, "Invalid DAV response", ex);
				
			} catch (HttpException ex) {
				if (ex.getCode() == HttpStatus.SC_UNAUTHORIZED) {
					Log.e(TAG, "HTTP Unauthorized " + ex.getCode(), ex);
					syncResult.stats.numAuthExceptions++;
				} else if (ex.isClientError()) {
					Log.e(TAG, "Hard HTTP error " + ex.getCode(), ex);
					syncResult.stats.numParseExceptions++;
				} else {
					Log.w(TAG, "Soft HTTP error" + ex.getCode(), ex);
					syncResult.stats.numIoExceptions++;
				}
				
			} catch (LocalStorageException ex) {
				syncResult.databaseError = true;
				Log.e(TAG, "Local storage (content provider) exception", ex);
			} catch (IOException ex) {
				syncResult.stats.numIoExceptions++;
				Log.e(TAG, "I/O error", ex);
			}
	}
}
