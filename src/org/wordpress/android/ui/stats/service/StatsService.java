package org.wordpress.android.ui.stats.service;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import android.app.Service;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.content.LocalBroadcastManager;

import com.android.volley.Request;
import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.wordpress.android.BuildConfig;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.StatsBarChartDataTable;
import org.wordpress.android.datasets.StatsClickGroupsTable;
import org.wordpress.android.datasets.StatsClicksTable;
import org.wordpress.android.datasets.StatsGeoviewsTable;
import org.wordpress.android.datasets.StatsReferrerGroupsTable;
import org.wordpress.android.datasets.StatsReferrersTable;
import org.wordpress.android.datasets.StatsSearchEngineTermsTable;
import org.wordpress.android.datasets.StatsTopPostsAndPagesTable;
import org.wordpress.android.models.StatsBarChartData;
import org.wordpress.android.models.StatsClick;
import org.wordpress.android.models.StatsClickGroup;
import org.wordpress.android.models.StatsGeoview;
import org.wordpress.android.models.StatsReferrer;
import org.wordpress.android.models.StatsReferrerGroup;
import org.wordpress.android.models.StatsSearchEngineTerm;
import org.wordpress.android.models.StatsSummary;
import org.wordpress.android.models.StatsTopPostsAndPages;
import org.wordpress.android.networking.RestClientUtils;
import org.wordpress.android.providers.StatsContentProvider;
import org.wordpress.android.ui.stats.StatsActivity;
import org.wordpress.android.ui.stats.StatsBarChartUnit;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.StatUtils;
import org.wordpress.android.util.StringUtils;

/**
 * Background service to retrieve latest stats - Uses a Thread to enqueue network calls in Volley; 
 * Volley takes care of handling multithreading. UI refresh is done by using a ThreadPoolExecutor with a single thread.
 */

public class StatsService extends Service {

    public static final String ARG_BLOG_ID = "blog_id";

    // broadcast action to notify clients of update start/end
    public static final String ACTION_STATS_UPDATING = "wp-stats-updating";
    public static final String EXTRA_IS_UPDATING = "is-updating";

    // broadcast action to notify clients when summary data has changed
    public static final String ACTION_STATS_SUMMARY_UPDATED = "STATS_SUMMARY_UPDATED";
    public static final String STATS_SUMMARY_UPDATED_EXTRA = "STATS_SUMMARY_UPDATED_EXTRA";
    
    protected static final long TWO_DAYS = 2 * 24 * 60 * 60 * 1000;
    
    private final Object mSyncObject = new Object();

    private String mBlogId;
    private LinkedList<Request<JSONObject>> statsNetworkRequests = new LinkedList<Request<JSONObject>>();
    private int numberOfNetworkCalls = -1; //The number of networks calls made by Stats. 
    private int numberOfFinishedNetworkCalls = 0;
    private ThreadPoolExecutor updateUIExecutor;
    private Thread orchestrator;
    
    private LinkedList<Uri> mUpdateUIMatch = new LinkedList<Uri>(); //Keep a reference to content provider URIs already updated
    
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.i(T.STATS, "service created");
    }

    @Override
    public void onDestroy() {
        stopRefresh();
        AppLog.i(T.STATS, "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String blogId = StringUtils.notNullStr(intent.getStringExtra(ARG_BLOG_ID));

        if (mBlogId == null) {
            startTasks(blogId, startId);
        } else if (blogId.equals(mBlogId)) {
            //already running on the same blogID
            //Do nothing
            AppLog.i(T.STATS, "StatsService is already running on this blogID - " + mBlogId);
        } else {
            //stats is running on a different blogID
            stopRefresh();
            startTasks(blogId, startId);
        }
        
        this.mBlogId = blogId;       
        return START_NOT_STICKY;
    }
    
    private void stopRefresh() {
        for (Request<JSONObject> req : statsNetworkRequests) {
            if (req != null && !req.hasHadResponseDelivered() && !req.isCanceled()) {
                req.cancel();
            }
        }
        statsNetworkRequests.clear();
        if (orchestrator != null) {
            orchestrator.interrupt();
        }
        orchestrator = null;
        this.mBlogId = null;
    }
    
    private void startTasks(final String blogId, final int startId) {

        orchestrator = new Thread() {
            @Override
            public void run() {
                updateUIExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1); //single thread otherwise the UI is sluggish
                RestClientUtils restClientUtils = WordPress.getRestClientUtils();
                final String today = StatUtils.getCurrentDate();
                final String yesterday = StatUtils.getYesterdaysDate();
            
                AppLog.i(T.STATS, "update started for blogID - " + blogId);
                broadcastUpdate(true);
                
                // visitors and views
                String path = String.format("sites/%s/stats", blogId);
                statsNetworkRequests.add(restClientUtils.get(path, statsSummaryRestListener, statsSummaryErrListener));
                
                path = getBarChartPath(StatsBarChartUnit.WEEK, 30);
                BarChartListener barChartRestListener = new BarChartListener(StatsBarChartUnit.WEEK);
                statsNetworkRequests.add(restClientUtils.get(path, barChartRestListener, barChartRestListener));
                path = getBarChartPath(StatsBarChartUnit.MONTH, 30);
                barChartRestListener = new BarChartListener(StatsBarChartUnit.MONTH);
                statsNetworkRequests.add(restClientUtils.get(path, barChartRestListener, barChartRestListener));
                
                // top posts and pages
                path = String.format("sites/%s/stats/top-posts?date=%s", mBlogId, today);
                TopPostAndPageListener topPostAndPageRestListener = new TopPostAndPageListener(today);
                statsNetworkRequests.add(restClientUtils.get(path, topPostAndPageRestListener, topPostAndPageRestListener));
                path = String.format("sites/%s/stats/top-posts?date=%s", mBlogId, yesterday);
                topPostAndPageRestListener = new TopPostAndPageListener(yesterday);
                statsNetworkRequests.add(restClientUtils.get(path, new TopPostAndPageListener(yesterday), topPostAndPageRestListener));
                
                // referrers
                path = String.format("sites/%s/stats/referrers?date=%s", mBlogId, today);
                ReferrersListener referrersListener = new ReferrersListener(today);
                statsNetworkRequests.add(restClientUtils.get(path, referrersListener, referrersListener));
                path = String.format("sites/%s/stats/referrers?date=%s", mBlogId, yesterday);
                referrersListener = new ReferrersListener(yesterday);
                statsNetworkRequests.add(restClientUtils.get(path, referrersListener, referrersListener));
                
                // clicks
                path = String.format("sites/%s/stats/clicks?date=%s", mBlogId, today);
                ClicksListener clicksListener = new ClicksListener(today);
                statsNetworkRequests.add(restClientUtils.get(path, clicksListener, clicksListener));
                path = String.format("sites/%s/stats/clicks?date=%s", mBlogId, yesterday);
                clicksListener = new ClicksListener(yesterday);
                statsNetworkRequests.add(restClientUtils.get(path, clicksListener, clicksListener));

                // search engine terms
                path = String.format("sites/%s/stats/search-terms?date=%s", mBlogId, today);
                SearchEngineTermsListener searchEngineTermsListener = new SearchEngineTermsListener(today);
                statsNetworkRequests.add(restClientUtils.get(path, searchEngineTermsListener, searchEngineTermsListener));
                path = String.format("sites/%s/stats/search-terms?date=%s", mBlogId, yesterday);
                searchEngineTermsListener = new SearchEngineTermsListener(yesterday);
                statsNetworkRequests.add(restClientUtils.get(path, searchEngineTermsListener, searchEngineTermsListener));

                // views by country - put at the end since this will start other networks calls on finish
                path = String.format("sites/%s/stats/country-views?date=%s", mBlogId, today);
                ViewsByCountryListener viewsByCountryListener = new ViewsByCountryListener(today);
                statsNetworkRequests.add(restClientUtils.get(path, viewsByCountryListener, viewsByCountryListener));
                path = String.format("sites/%s/stats/country-views?date=%s", mBlogId, yesterday);
                viewsByCountryListener = new ViewsByCountryListener(yesterday);
                statsNetworkRequests.add(restClientUtils.get(path, viewsByCountryListener, viewsByCountryListener));
                
                numberOfNetworkCalls = statsNetworkRequests.size();
                
                while (!isDone()) {
                    waitForResponse();
                }

                //Initiates an orderly shutdown in which previously submitted tasks are executed, but no new tasks will be accepted.
                //At this point all Threads previously enqueued in updateUIExecutor already finished their execution.
                updateUIExecutor.shutdown();
                mBlogId = null;

                broadcastUpdate(false);
                stopSelf(startId);               
            } //end run
        };
        
        orchestrator.start(); 
    }

    
    
    /**
     * 
     * This method returns true when network calls that update data for the current content provider URI are both finished.
     * 
     * In details, the code checks if the current content URI (say StatsContentProvider.STATS_TOP_POSTS_AND_PAGES_URI) is already
     * in the list. If it's already there, this code is called from the 2nd listener, and we can refresh both UI Fragments.
     * 
     * @param uri The current content provider URI
     * @return true if Fragments can be refreshed.
     */
    private boolean shouldUpdateFragments(Uri uri) {
        if (mUpdateUIMatch.contains(uri) ) {
            return true;
        }
        
        mUpdateUIMatch.add(uri);
        return false;
    }
    
    private class SearchEngineTermsListener extends AbsListener {

        SearchEngineTermsListener(String date){
            this.date = date;
        }

        @Override
        protected void parseResponse(final JSONObject response) throws JSONException, RemoteException, OperationApplicationException {
            String date = response.getString("date");
            long dateMs = StatUtils.toMs(date);

            ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();

            ContentProviderOperation delete_op = ContentProviderOperation.newDelete(StatsContentProvider.STATS_SEARCH_ENGINE_TERMS_URI).withSelection("blogId=? AND (date=? OR date<=?)",
                    new String[] { mBlogId, dateMs + "", (dateMs - TWO_DAYS) + "" }).build();

            operations.add(delete_op);

            JSONArray results = response.getJSONArray("search-terms");

            int count = Math.min(results.length(), StatsActivity.STATS_GROUP_MAX_ITEMS);
            for (int i = 0; i < count; i++ ) {
                JSONArray result = results.getJSONArray(i);
                StatsSearchEngineTerm stat = new StatsSearchEngineTerm(mBlogId, date, result);
                ContentValues values = StatsSearchEngineTermsTable.getContentValues(stat);
                getContentResolver().insert(StatsContentProvider.STATS_SEARCH_ENGINE_TERMS_URI, values);

                ContentProviderOperation insert_op = ContentProviderOperation.newInsert(StatsContentProvider.STATS_SEARCH_ENGINE_TERMS_URI).withValues(values).build();
                operations.add(insert_op);
            }

            getContentResolver().applyBatch(BuildConfig.STATS_PROVIDER_AUTHORITY, operations);
        }

        @Override
        protected Uri getStatsContentProviderUpdateURI() {
            return StatsContentProvider.STATS_SEARCH_ENGINE_TERMS_URI;
        }
    }
  
    private class ClicksListener extends AbsListener {

        ClicksListener(String date){
            this.date = date;
        }

        @Override
        protected void parseResponse(final JSONObject response) throws JSONException, RemoteException, OperationApplicationException {
            String date = response.getString("date");
            long dateMs = StatUtils.toMs(date);

            ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();

            // delete data with the same date, and data older than two days ago (keep yesterday's data)
            ContentProviderOperation delete_group = ContentProviderOperation.newDelete(StatsContentProvider.STATS_CLICK_GROUP_URI).withSelection("blogId=? AND (date=? OR date<=?)",
                    new String[] { mBlogId, dateMs + "", (dateMs - TWO_DAYS) + "" }).build();
            ContentProviderOperation delete_child = ContentProviderOperation.newDelete(StatsContentProvider.STATS_CLICKS_URI).withSelection("blogId=? AND (date=? OR date<=?)",
                    new String[] { mBlogId, dateMs + "", (dateMs - TWO_DAYS) + "" }).build();

            operations.add(delete_group);
            operations.add(delete_child);


            JSONArray groups = response.getJSONArray("clicks");

            // insert groups, limited to the number that can actually be displayed
            int groupsCount = Math.min(groups.length(), StatsActivity.STATS_GROUP_MAX_ITEMS);
            for (int i = 0; i < groupsCount; i++ ) {
                JSONObject group = groups.getJSONObject(i);
                StatsClickGroup statGroup = new StatsClickGroup(mBlogId, date, group);
                ContentValues values = StatsClickGroupsTable.getContentValues(statGroup);

                ContentProviderOperation insert_group = ContentProviderOperation.newInsert(StatsContentProvider.STATS_CLICK_GROUP_URI).withValues(values).build();
                operations.add(insert_group);

                // insert children if there are any, limited to the number that can be displayed
                JSONArray clicks = group.getJSONArray("results");
                int childCount = Math.min(clicks.length(), StatsActivity.STATS_CHILD_MAX_ITEMS);
                if (childCount > 1) {
                    for (int j = 0; j < childCount; j++) {
                        StatsClick stat = new StatsClick(mBlogId, date, statGroup.getGroupId(), clicks.getJSONArray(j));
                        ContentValues v = StatsClicksTable.getContentValues(stat);
                        ContentProviderOperation insert_child = ContentProviderOperation.newInsert(StatsContentProvider.STATS_CLICKS_URI).withValues(v).build();
                        operations.add(insert_child);
                    }
                }
            }

            getContentResolver().applyBatch(BuildConfig.STATS_PROVIDER_AUTHORITY, operations);
            getContentResolver().notifyChange(StatsContentProvider.STATS_CLICKS_URI, null);
        }

        @Override
        protected Uri getStatsContentProviderUpdateURI() {
            return StatsContentProvider.STATS_CLICK_GROUP_URI;
        }
    }
   
    private class ReferrersListener extends AbsListener {

        ReferrersListener(String date){
            this.date = date;
        }

        @Override
        protected void parseResponse(final JSONObject response) throws JSONException, RemoteException, OperationApplicationException {
                String date = response.getString("date");
                long dateMs = StatUtils.toMs(date);

                ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();

                // delete data with the same date, and data older than two days ago (keep yesterday's data)
                ContentProviderOperation delete_group_op = ContentProviderOperation.newDelete(StatsContentProvider.STATS_REFERRER_GROUP_URI)
                        .withSelection("blogId=? AND (date=? OR date<=?)", new String[] { mBlogId, dateMs + "", (dateMs - TWO_DAYS) + "" }).build();
                operations.add(delete_group_op);

                ContentProviderOperation delete_op = ContentProviderOperation.newDelete(StatsContentProvider.STATS_REFERRERS_URI)
                        .withSelection("blogId=? AND (date=? OR date<=?)", new String[] { mBlogId, dateMs + "", (dateMs - TWO_DAYS) + "" }).build();
                operations.add(delete_op);

                JSONArray groups = response.getJSONArray("referrers");
                int groupsCount = Math.min(groups.length(), StatsActivity.STATS_GROUP_MAX_ITEMS);

                // insert groups
                for (int i = 0; i < groupsCount; i++ ) {
                    JSONObject group = groups.getJSONObject(i);
                    StatsReferrerGroup statGroup = new StatsReferrerGroup(mBlogId, date, group);
                    ContentValues values = StatsReferrerGroupsTable.getContentValues(statGroup);
                    ContentProviderOperation insert_group_op = ContentProviderOperation.newInsert(StatsContentProvider.STATS_REFERRER_GROUP_URI).withValues(values).build();
                    operations.add(insert_group_op);

                    // insert children, only if there is more than one entry
                    JSONArray referrers = group.getJSONArray("results");
                    int childCount = Math.min(referrers.length(), StatsActivity.STATS_CHILD_MAX_ITEMS);
                    if (childCount > 1) {
                        for (int j = 0; j < childCount; j++) {
                            StatsReferrer stat = new StatsReferrer(mBlogId, date, statGroup.getGroupId(), referrers.getJSONArray(j));
                            ContentValues v = StatsReferrersTable.getContentValues(stat);
                            ContentProviderOperation insert_child_op = ContentProviderOperation.newInsert(StatsContentProvider.STATS_REFERRERS_URI).withValues(v).build();
                            operations.add(insert_child_op);
                        }
                    }
                }

                getContentResolver().applyBatch(BuildConfig.STATS_PROVIDER_AUTHORITY, operations);
                //getContentResolver().notifyChange(StatsContentProvider.STATS_REFERRER_GROUP_URI, null);
                getContentResolver().notifyChange(StatsContentProvider.STATS_REFERRERS_URI, null);
        }

        @Override
        protected Uri getStatsContentProviderUpdateURI() {
           return StatsContentProvider.STATS_REFERRER_GROUP_URI;
        }
    }
    
    private class ViewsByCountryListener extends AbsListener {

        ViewsByCountryListener(String date){
            this.date = date;
        }

        @Override
        protected void parseResponse(final JSONObject response) throws JSONException, RemoteException, OperationApplicationException {
            if (!response.has("country-views")) {
                return;
            }

            JSONArray results = response.getJSONArray("country-views");
            int count = Math.min(results.length(), StatsActivity.STATS_GROUP_MAX_ITEMS);
            String date = response.getString("date");
            long dateMs = StatUtils.toMs(date);
            ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();

            if (count > 0) {
                // delete data with the same date, and data older than two days ago (keep yesterday's data)
                ContentProviderOperation delete_op = ContentProviderOperation.newDelete(StatsContentProvider.STATS_GEOVIEWS_URI)
                        .withSelection("blogId=? AND (date=? OR date<=?)", new String[]{mBlogId, dateMs + "", (dateMs - TWO_DAYS) + ""}).build();
                operations.add(delete_op);
            }

            for (int i = 0; i < count; i++ ) {
                JSONObject result = results.getJSONObject(i);
                StatsGeoview stat = new StatsGeoview(mBlogId, result);
                ContentValues values = StatsGeoviewsTable.getContentValues(stat);
                ContentProviderOperation op = ContentProviderOperation.newInsert(StatsContentProvider.STATS_GEOVIEWS_URI).withValues(values).build();
                operations.add(op);
            }

            getContentResolver().applyBatch(BuildConfig.STATS_PROVIDER_AUTHORITY, operations);
        }

        @Override
        protected Uri getStatsContentProviderUpdateURI() {
           return StatsContentProvider.STATS_GEOVIEWS_URI;
        }
    }
    
    private class TopPostAndPageListener extends AbsListener {

        TopPostAndPageListener(String date){
            this.date = date;
        }

        @Override
        protected void parseResponse(final JSONObject response) throws JSONException, RemoteException, OperationApplicationException {
            if (!response.has("top-posts")) {
                return;
            }

            JSONArray results = response.getJSONArray("top-posts");
            int count = Math.min(results.length(), StatsActivity.STATS_GROUP_MAX_ITEMS);

            String date = response.getString("date");
            long dateMs = StatUtils.toMs(date);

            ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();
            // delete data with the same date, and data older than two days ago (keep yesterday's data)
            ContentProviderOperation delete_op = ContentProviderOperation.newDelete(StatsContentProvider.STATS_TOP_POSTS_AND_PAGES_URI)
                    .withSelection("blogId=? AND (date=? OR date<=?)", new String[] { mBlogId, dateMs + "", (dateMs - TWO_DAYS) + "" }).build();
            operations.add(delete_op);

            for (int i = 0; i < count; i++ ) {
                JSONObject result = results.getJSONObject(i);
                StatsTopPostsAndPages stat = new StatsTopPostsAndPages(mBlogId, result);
                ContentValues values = StatsTopPostsAndPagesTable.getContentValues(stat);
                ContentProviderOperation op = ContentProviderOperation.newInsert(StatsContentProvider.STATS_TOP_POSTS_AND_PAGES_URI).withValues(values).build();
                operations.add(op);
            }

            getContentResolver().applyBatch(BuildConfig.STATS_PROVIDER_AUTHORITY, operations);
            // getContentResolver().notifyChange(StatsContentProvider.STATS_TOP_POSTS_AND_PAGES_URI, null);
        }

        @Override
        protected Uri getStatsContentProviderUpdateURI() {
           return StatsContentProvider.STATS_TOP_POSTS_AND_PAGES_URI;
        }
    }
    
    
    private abstract class AbsListener implements RestRequest.Listener, RestRequest.ErrorListener {
        protected String date;
        
        protected abstract Uri getStatsContentProviderUpdateURI();
        protected abstract void parseResponse(final JSONObject response) throws JSONException, RemoteException, OperationApplicationException;
        
        @Override
        public void onResponse(final JSONObject response) {
            AppLog.d(T.STATS, this.getClass().getName() + " " + date + " responded OK");
            if(!updateUIExecutor.isShutdown() && !updateUIExecutor.isTerminated() && !updateUIExecutor.isTerminating())
                updateUIExecutor.submit(
                        new Thread() {
                            @Override
                            public void run() {
                                numberOfFinishedNetworkCalls++;
                                if (response != null) {
                                    try {
                                        parseResponse(response);
                                    } catch (JSONException e) {
                                        AppLog.e(AppLog.T.STATS, e);
                                    } catch (RemoteException e) {
                                        AppLog.e(AppLog.T.STATS, e);
                                    } catch (OperationApplicationException e) {
                                        AppLog.e(AppLog.T.STATS, e);
                                    }
                                }
                                notifyResponseReceived();
                                if (shouldUpdateFragments(getStatsContentProviderUpdateURI())) { //Update the 2 Fragments only when both network calls are finished
                                    getContentResolver().notifyChange(getStatsContentProviderUpdateURI(), null);
                                }
                            }
                        });
        }
        
        @Override
        public void onErrorResponse(final VolleyError volleyError) {
            AppLog.d(T.STATS, this.getClass().getName() + " " + date + " responded with Error");
            if(!updateUIExecutor.isShutdown() && !updateUIExecutor.isTerminated() && !updateUIExecutor.isTerminating())
                updateUIExecutor.submit(new Thread() {
                    @Override
                    public void run() {
                        numberOfFinishedNetworkCalls++;
                        if (volleyError != null) {
                            AppLog.e(T.STATS, "Error while reading Stats - " + volleyError.getMessage(), volleyError);
                        }
                        notifyResponseReceived();
                        if (shouldUpdateFragments(getStatsContentProviderUpdateURI())) { //Update the 2 Fragments only when both network calls are finished
                            getContentResolver().notifyChange(getStatsContentProviderUpdateURI(), null);
                        }
                    }
                });
        }
    }
    
    private String getBarChartPath(StatsBarChartUnit mBarChartUnit, int quantity) {
        String path = String.format("sites/%s/stats/visits", mBlogId);
        String unit = mBarChartUnit.name().toLowerCase(Locale.ENGLISH);
        path += String.format("?unit=%s", unit);
        if (quantity > 0) {
            path += String.format("&quantity=%d", quantity);
        }
        return path;
    }
    
    private class BarChartListener extends AbsListener {

        private StatsBarChartUnit mBarChartUnit;
        BarChartListener(StatsBarChartUnit barChartUnit){
            this.mBarChartUnit = barChartUnit;
            this.date = barChartUnit.toString();
        }

        @Override
        protected Uri getStatsContentProviderUpdateURI() {
           return StatsContentProvider.STATS_BAR_CHART_DATA_URI;
        }

        @Override
        protected void parseResponse(JSONObject response) throws JSONException, RemoteException, OperationApplicationException {
            if (!response.has("data")) {
                return;
            }

            Uri uri = StatsContentProvider.STATS_BAR_CHART_DATA_URI;
            JSONArray results = response.getJSONArray("data");

            int count = results.length();

            ArrayList<ContentProviderOperation> operations = new ArrayList<ContentProviderOperation>();

            // delete old stats and insert new ones
            if (count > 0) {
                ContentProviderOperation op = ContentProviderOperation.newDelete(uri).withSelection("blogId=? AND unit=?", new String[] { mBlogId, mBarChartUnit.name() }).build();
                operations.add(op);
            }

            for (int i = 0; i < count; i++ ) {
                JSONArray result = results.getJSONArray(i);
                StatsBarChartData stat = new StatsBarChartData(mBlogId, mBarChartUnit, result);
                ContentValues values = StatsBarChartDataTable.getContentValues(stat);

                if (values != null && uri != null) {
                    ContentProviderOperation op = ContentProviderOperation.newInsert(uri).withValues(values).build();
                    operations.add(op);
                }
            }

            getContentResolver().applyBatch(BuildConfig.STATS_PROVIDER_AUTHORITY, operations);
        }
    }
    
    RestRequest.Listener statsSummaryRestListener = new RestRequest.Listener() {
        @Override
        public void onResponse(final JSONObject jsonObject) {
            if(!updateUIExecutor.isShutdown() && !updateUIExecutor.isTerminated() && !updateUIExecutor.isTerminating())
                updateUIExecutor.submit(new Thread() {
                    @Override
                    public void run() {
                        AppLog.d(T.STATS, "Stats Summary Call responded");
                        numberOfFinishedNetworkCalls++;

                        try{
                            if (jsonObject == null)
                                return;

                            // save summary, then send broadcast that they've changed
                            StatUtils.saveSummary(mBlogId, jsonObject);
                            StatsSummary stats = StatUtils.getSummary(mBlogId);
                            if (stats != null) {
                                LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(WordPress.getContext());
                                Intent intent = new Intent(StatsService.ACTION_STATS_SUMMARY_UPDATED);
                                intent.putExtra(StatsService.STATS_SUMMARY_UPDATED_EXTRA, stats);
                                lbm.sendBroadcast(intent);
                            }
                        } finally {
                            notifyResponseReceived();
                        }
                    }
                });
        }
    };
    
    RestRequest.ErrorListener statsSummaryErrListener = new RestRequest.ErrorListener() {
        @Override
        public void onErrorResponse(final VolleyError volleyError) {
            if(!updateUIExecutor.isShutdown() && !updateUIExecutor.isTerminated() && !updateUIExecutor.isTerminating())
                updateUIExecutor.submit(new Thread() {
                    @Override
                    public void run() {
                        numberOfFinishedNetworkCalls++;
                        if (volleyError != null) {
                            AppLog.e(T.STATS, "Error while reading Stats - " + volleyError.getMessage(), volleyError);
                        }
                        notifyResponseReceived();
                    }
                });
        }
    };
    
    protected boolean isDone() {
        return numberOfFinishedNetworkCalls == numberOfNetworkCalls;
    }
    
    private void waitForResponse() {
        synchronized (mSyncObject) {
            try {
                mSyncObject.wait();
            } catch (InterruptedException e) {
                AppLog.w(T.STATS, "Orchestrator interrupted");
            }
        }
    }

    /*
     * called when either (a) the response has been received and parsed, or (b) the request failed
     */
    private void notifyResponseReceived() {
        synchronized (mSyncObject) {
            mSyncObject.notify();
        }
    }

    /*
     * broadcast that the update has started/ended - used by StatsActivity to animate refresh
     * icon while update is in progress
     */
    private void broadcastUpdate(boolean isUpdating) {
        Intent intent = new Intent()
                .setAction(ACTION_STATS_UPDATING)
                .putExtra(EXTRA_IS_UPDATING, isUpdating);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}