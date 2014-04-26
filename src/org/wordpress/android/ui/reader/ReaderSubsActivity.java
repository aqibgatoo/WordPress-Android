package org.wordpress.android.ui.reader;

import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.PagerTabStrip;
import android.support.v4.view.ViewPager;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockFragmentActivity;

import org.wordpress.android.R;
import org.wordpress.android.datasets.ReaderTagTable;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.models.ReaderTag.ReaderTagType;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderBlogActions;
import org.wordpress.android.ui.reader.actions.ReaderTagActions;
import org.wordpress.android.ui.reader.actions.ReaderTagActions.TagAction;
import org.wordpress.android.ui.reader.adapters.ReaderBlogAdapter.ReaderBlogType;
import org.wordpress.android.ui.reader.adapters.ReaderTagAdapter;
import org.wordpress.android.util.EditTextUtils;
import org.wordpress.android.util.MessageBarUtils;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.ToastUtils;
import org.wordpress.android.util.stats.AnalyticsTracker;

import java.util.ArrayList;
import java.util.List;

/**
 * activity which shows the user's subscriptions and recommended subscriptions - includes
 * followed tags, popular tags, followed blogs, and recommended blogs
 */

public class ReaderSubsActivity extends SherlockFragmentActivity
                                implements ReaderTagAdapter.TagActionListener {

    private EditText mEditAddTag;
    private TagPageAdapter mPageAdapter;

    private boolean mTagsChanged;
    private String mLastAddedTag;
    private boolean mAlreadyUpdated;

    protected static final String KEY_TAGS_CHANGED   = "tags_changed";
    protected static final String KEY_LAST_ADDED_TAG = "last_added_tag";
    private static final String KEY_ALREADY_UPDATED = "is_updated";

    private static final int TAB_IDX_FOLLOWED_TAGS = 0;
    private static final int TAB_IDX_SUGGESTED_TAGS = 1;
    private static final int TAB_IDX_FOLLOWED_BLOGS = 2;
    private static final int TAB_IDX_RECOMMENDED_BLOGS = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.reader_activity_subs);

        if (savedInstanceState != null) {
            mTagsChanged = savedInstanceState.getBoolean(KEY_TAGS_CHANGED);
            mLastAddedTag = savedInstanceState.getString(KEY_LAST_ADDED_TAG);
            mAlreadyUpdated = savedInstanceState.getBoolean(KEY_ALREADY_UPDATED);
        }

        ViewPager viewPager = (ViewPager) findViewById(R.id.viewpager);
        viewPager.setAdapter(getPageAdapter());

        PagerTabStrip pagerTabStrip = (PagerTabStrip) findViewById(R.id.pager_tab_strip);
        pagerTabStrip.setTabIndicatorColorResource(R.color.blue_light);

        mEditAddTag = (EditText) findViewById(R.id.edit_add);
        mEditAddTag.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    addCurrentTag();
                }
                return false;
            }
        });

        final ImageButton btnAddTag = (ImageButton) findViewById(R.id.btn_add);
        btnAddTag.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addCurrentTag();
            }
        });

        // update list of tags and blogs from the server
        if (!mAlreadyUpdated) {
            updateTagList();
            updateFollowedBlogs();
            updateRecommendedBlogs();
            mAlreadyUpdated = true;
        }
    }

    private TagPageAdapter getPageAdapter() {
        if (mPageAdapter == null) {
            List<Fragment> fragments = new ArrayList<Fragment>();

            // add tag fragments
            fragments.add(ReaderTagFragment.newInstance(ReaderTagType.SUBSCRIBED));
            fragments.add(ReaderTagFragment.newInstance(ReaderTagType.RECOMMENDED));

            // add blog fragments
            fragments.add(ReaderBlogFragment.newInstance(ReaderBlogType.FOLLOWED));
            fragments.add(ReaderBlogFragment.newInstance(ReaderBlogType.RECOMMENDED));

            mPageAdapter = new TagPageAdapter(getSupportFragmentManager(), fragments);
        }
        return mPageAdapter;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_TAGS_CHANGED, mTagsChanged);
        outState.putBoolean(KEY_ALREADY_UPDATED, mAlreadyUpdated);
        if (mLastAddedTag != null) {
            outState.putString(KEY_LAST_ADDED_TAG, mLastAddedTag);
        }
    }

    @Override
    public void onBackPressed() {
        if (mTagsChanged) {
            Bundle bundle = new Bundle();
            bundle.putBoolean(KEY_TAGS_CHANGED, true);
            if (mLastAddedTag !=null && ReaderTagTable.tagExists(mLastAddedTag)) {
                bundle.putString(KEY_LAST_ADDED_TAG, mLastAddedTag);
            }
            Intent intent = new Intent();
            intent.putExtras(bundle);
            setResult(RESULT_OK, intent);
        }

        super.onBackPressed();
    }

    /*
     * add the tag in the EditText to the user's followed tags
     */
    private void addCurrentTag() {
        String tagName = EditTextUtils.getText(mEditAddTag);
        if (TextUtils.isEmpty(tagName)) {
            return;
        }

        if (!NetworkUtils.checkConnection(this)) {
            return;
        }

        if (ReaderTagTable.tagExists(tagName)) {
            ToastUtils.showToast(this, R.string.reader_toast_err_tag_exists, ToastUtils.Duration.LONG);
            return;
        }

        if (!ReaderTag.isValidTagName(tagName)) {
            ToastUtils.showToast(this, R.string.reader_toast_err_tag_invalid, ToastUtils.Duration.LONG);
            return;
        }

        mEditAddTag.setText(null);
        EditTextUtils.hideSoftInput(mEditAddTag);

        onTagAction(TagAction.ADD, tagName);
    }

    /*
     * triggered by active fragment's adapter when user chooses to add/remove a tag, or
     * from this activity when user chooses to add a tag
     */
    @Override
    public void onTagAction(final TagAction action, final String tagName) {
        if (TextUtils.isEmpty(tagName)) {
            return;
        }

        if (!NetworkUtils.checkConnection(this)) {
            return;
        }

        final String messageBarText;
        final MessageBarUtils.MessageBarType messageBarType;
        switch (action) {
            case ADD:
                AnalyticsTracker.track(AnalyticsTracker.Stat.READER_FOLLOWED_READER_TAG);
                messageBarText = getString(R.string.reader_label_added_tag, tagName);
                messageBarType = MessageBarUtils.MessageBarType.INFO;
                break;
            case DELETE:
                AnalyticsTracker.track(AnalyticsTracker.Stat.READER_UNFOLLOWED_READER_TAG);
                messageBarText = getString(R.string.reader_label_removed_tag, tagName);
                messageBarType = MessageBarUtils.MessageBarType.ALERT;
                break;
            default :
                return;
        }
        MessageBarUtils.showMessageBar(this, messageBarText, messageBarType, null);

        ReaderActions.ActionListener actionListener = new ReaderActions.ActionListener() {
            @Override
            public void onActionResult(boolean succeeded) {
                // handle failure when adding/removing tags below
                if (!succeeded && !isFinishing()) {
                    getPageAdapter().refreshTags();
                    switch (action) {
                        case ADD:
                            ToastUtils.showToast(ReaderSubsActivity.this, R.string.reader_toast_err_add_tag);
                            mLastAddedTag = null;
                            break;
                        case DELETE:
                            ToastUtils.showToast(ReaderSubsActivity.this, R.string.reader_toast_err_remove_tag);
                            break;
                    }
                }
            }
        };

        switch (action) {
            case ADD:
                if (ReaderTagActions.performTagAction(TagAction.ADD, tagName, actionListener)) {
                    getPageAdapter().refreshTags(tagName);
                    mTagsChanged = true;
                    mLastAddedTag = tagName;
                }
                break;

            case DELETE:
                if (ReaderTagActions.performTagAction(TagAction.DELETE, tagName, actionListener)) {
                    getPageAdapter().refreshTags();
                    if (mLastAddedTag !=null && mLastAddedTag.equals(tagName)) {
                        mLastAddedTag = null;
                    }
                    mTagsChanged = true;
                }
                break;
        }
    }

    /*
     * request latest list of tags from the server
     */
    void updateTagList() {
        ReaderActions.UpdateResultListener listener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                if (result == ReaderActions.UpdateResult.CHANGED) {
                    mTagsChanged = true;
                    getPageAdapter().refreshTags();
                }
            }
        };
        ReaderTagActions.updateTags(listener);
    }

    /*
     * request latest recommended blogs
     */
    void updateRecommendedBlogs() {
        ReaderActions.UpdateResultListener listener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                if (result == ReaderActions.UpdateResult.CHANGED) {
                    getPageAdapter().refreshBlogs();
                }
            }
        };
        ReaderBlogActions.updateRecommendedBlogs(listener);
    }

    /*
     * request latest followed blogs
     */
    void updateFollowedBlogs() {
        ReaderActions.UpdateResultListener listener = new ReaderActions.UpdateResultListener() {
            @Override
            public void onUpdateResult(ReaderActions.UpdateResult result) {
                if (result == ReaderActions.UpdateResult.CHANGED) {
                    getPageAdapter().refreshBlogs();
                }
            }
        };
        ReaderBlogActions.updateFollowedBlogs(listener);
    }

    private class TagPageAdapter extends FragmentPagerAdapter {
        private final List<Fragment> mFragments;

        TagPageAdapter(FragmentManager fm, List<Fragment> fragments) {
            super(fm);
            mFragments = fragments;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            switch (position) {
                case TAB_IDX_FOLLOWED_TAGS:
                    return getString(R.string.reader_title_followed_tags);
                case TAB_IDX_SUGGESTED_TAGS:
                    return getString(R.string.reader_title_popular_tags);
                case TAB_IDX_FOLLOWED_BLOGS:
                    return getString(R.string.reader_title_followed_blogs);
                case TAB_IDX_RECOMMENDED_BLOGS:
                    return getString(R.string.reader_title_recommended_blogs);
                default:
                    return super.getPageTitle(position);
            }
        }

        @Override
        public Fragment getItem(int position) {
            return mFragments.get(position);
        }

        @Override
        public int getCount() {
            return mFragments.size();
        }

        // refresh all tag fragments
        private void refreshTags() {
            refreshTags(null);
        }
        private void refreshTags(final String scrollToTagName) {
            for (Fragment fragment: mFragments) {
                if (fragment instanceof ReaderTagFragment) {
                    ((ReaderTagFragment)fragment).refreshTags(scrollToTagName);
                }
            }
        }

        // refresh all blog fragments
        private void refreshBlogs() {
            for (Fragment fragment: mFragments) {
                if (fragment instanceof ReaderBlogFragment) {
                    ((ReaderBlogFragment)fragment).refreshBlogs();
                }
            }
        }
    }
}