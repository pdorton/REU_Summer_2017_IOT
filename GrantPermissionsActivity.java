/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.permission.ui;

import static android.content.pm.PackageManager.PERMISSION_DENIED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PermissionInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.hardware.camera2.utils.ArrayUtils;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;

import com.android.packageinstaller.DeviceUtils;
import com.android.packageinstaller.R;
import com.android.packageinstaller.permission.model.AppPermissionGroup;
import com.android.packageinstaller.permission.model.AppPermissions;
import com.android.packageinstaller.permission.model.Permission;
import com.android.packageinstaller.permission.utils.SafetyNetLogger;
import com.android.packageinstaller.permission.utils.Utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.acl.LastOwnerException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Hashtable;
import java.util.ListIterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;

import java.lang.Math;

public class GrantPermissionsActivity extends OverlayTouchActivity
        implements GrantPermissionsViewHandler.ResultListener 
        {

    private static final String LOG_TAG = "GrantPermissionsActivity";
    private static float LT_OFFER_CUTOFF = 2; /*Removed "final" flag to allow this number to be reassigned dynamically*/


    private String[] mRequestedPermissions;
    private int[] mGrantResults;
    private LottoTrader lottoTrader;

    private LinkedHashMap<String, GroupState> mRequestGrantPermissionGroups = new LinkedHashMap<>();

    private GrantPermissionsViewHandler mViewHandler;
    private AppPermissions mAppPermissions;

    boolean mResultSet;

    @Override
    public void onCreate(Bundle icicle) 
    {
        super.onCreate(icicle);
        setFinishOnTouchOutside(false);

        lottoTrader = new LottoTrader(this);
        setTitle(R.string.permission_request_title);

        if (DeviceUtils.isTelevision(this)) 
        {
            mViewHandler = new com.android.packageinstaller.permission.ui.television
                    .GrantPermissionsViewHandlerImpl(this).setResultListener(this);
        } 

        else if (DeviceUtils.isWear(this)) 
        {
            mViewHandler = new GrantPermissionsWatchViewHandler(this).setResultListener(this);
        } 

        else 
        {
            mViewHandler = new com.android.packageinstaller.permission.ui.handheld
                    .GrantPermissionsViewHandlerImpl(this).setResultListener(this);
        }

        mRequestedPermissions = getIntent().getStringArrayExtra(
                PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES);
        if (mRequestedPermissions == null) 
        {
            mRequestedPermissions = new String[0];
        }

        final int requestedPermCount = mRequestedPermissions.length;
        mGrantResults = new int[requestedPermCount];

        if (requestedPermCount == 0) 
        {
            setResultAndFinish();
            return;
        }

        PackageInfo callingPackageInfo = getCallingPackageInfo();

        DevicePolicyManager devicePolicyManager = getSystemService(DevicePolicyManager.class);
        final int permissionPolicy = devicePolicyManager.getPermissionPolicy(null);

        // If calling package is null we default to deny all.
        updateDefaultResults(callingPackageInfo, permissionPolicy);

        if (callingPackageInfo == null) 
        {
            setResultAndFinish();
            return;
        }

        mAppPermissions = new AppPermissions(this, callingPackageInfo, null, false,
                new Runnable() {
                    @Override
                    public void run() {
                        setResultAndFinish();
                    }
                });

        for (AppPermissionGroup group : mAppPermissions.getPermissionGroups()) 
        {
            boolean groupHasRequestedPermission = false;
            for (String requestedPermission : mRequestedPermissions) 
            {
                if (group.hasPermission(requestedPermission)) 
                {
                    groupHasRequestedPermission = true;
                    break;
                }
            }
            if (!groupHasRequestedPermission) 
            {
                continue;
            }
            /* We allow the user to choose only non-fixed permissions. A permission
             is fixed either by device policy or the user denying with prejudice.*/
            if (!group.isUserFixed() && !group.isPolicyFixed()) 
            {
                switch (permissionPolicy) 
                {
                    case DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT: 
                    {
                        if (!group.areRuntimePermissionsGranted()) 
                        {
                            group.grantRuntimePermissions(false);
                        }
                        group.setPolicyFixed();
                    }
                    break;

                    case DevicePolicyManager.PERMISSION_POLICY_AUTO_DENY: 
                    {
                        if (group.areRuntimePermissionsGranted()) 
                        {
                            group.revokeRuntimePermissions(false);
                        }
                        group.setPolicyFixed();
                    }
                    break;

                    default: 
                    {
                        if (!group.areRuntimePermissionsGranted()) 
                        {
                            mRequestGrantPermissionGroups.put(group.getName(),
                                    new GroupState(group));
                        } 

                        else 
                        {
                            group.grantRuntimePermissions(false);
                            updateGrantResults(group);
                        }
                    }
                    break;
                }
            }
            else 
            {
                // if the permission is fixed, ensure that we return the right request result
                updateGrantResults(group);
            }
        }

        setContentView(mViewHandler.createView());

        Window window = getWindow();
        WindowManager.LayoutParams layoutParams = window.getAttributes();
        mViewHandler.updateWindowAttributes(layoutParams);
        window.setAttributes(layoutParams);

        if (!showNextPermissionGroupGrantRequest()) 
        {
            setResultAndFinish();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) 
    {
        View rootView = getWindow().getDecorView();
        if (rootView.getTop() != 0) {
            // We are animating the top view, need to compensate for that in motion events.
            ev.setLocation(ev.getX(), ev.getY() - rootView.getTop());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mViewHandler.saveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) 
    {
        super.onRestoreInstanceState(savedInstanceState);
        mViewHandler.loadInstanceState(savedInstanceState);
    }

    private boolean showNextPermissionGroupGrantRequest() 
    {
        final int groupCount = mRequestGrantPermissionGroups.size();

        int currentIndex = 0;
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) 
        {
            if (groupState.mState == GroupState.STATE_UNKNOWN) 
            {
                CharSequence appLabel = mAppPermissions.getAppLabel();
                CheckBox checkBox = (CheckBox) findViewById(R.id.do_not_ask_checkbox);
                if (checkBox != null) checkBox.setEnabled(false);

                SpannableString message;
                final long timeSinceDenied = lottoTrader.checkIfDeniedRecently(appLabel.toString(),
                        groupState.mGroup.getName());
                if (timeSinceDenied != -1) 
                {
                    // App just requested this permission and the user denied,
                    // make them wait for a bit
                    message = new SpannableString(getString(
                            R.string.permission_denied_recently_template, appLabel,
                            groupState.mGroup.getDescription(), (timeSinceDenied / 60000) + 1,
                            ((LottoTrader.DENIED_WAIT_PERIOD - timeSinceDenied) / 60000) + 1));
                    Button denyButton = (Button) findViewById(R.id.permission_deny_button);
                    denyButton.setText("Cancel");
                    Button allowButton = (Button) findViewById(R.id.permission_allow_button);
                    allowButton.setEnabled(false);
                } 
                else 
                {


                    final Double offer = Math.random() * /*generateDynamicOffer()*/ LT_OFFER_CUTOFF /*TODO: This is where to place the new function to generate the dynamic offer*/;
                    message = new SpannableString(getString(
                            R.string.permission_warning_template, offer, appLabel,
                            groupState.mGroup.getDescription()));
                    // Color offer amount
                    message.setSpan(new ForegroundColorSpan(Color.rgb(36, 135, 0)), 16, 22, 0);
                    message.setSpan(new StyleSpan(Typeface.BOLD), 16, 22, 0);
                    // Color the app name.
                    int appLabelStart = message.toString().indexOf(appLabel.toString(), 0);
                    int appLabelLength = appLabel.length();
                    message.setSpan(new StyleSpan(Typeface.BOLD), appLabelStart,
                            appLabelStart + appLabelLength, 0);
                    // Set the permission message as the title so it can be announced.
                    setTitle(message);
                }

                // Set the new grant view
                // TODO: Use a real message for the action. We need group action APIs
                Resources resources;
                try 
                {
                    resources = getPackageManager().getResourcesForApplication(
                            groupState.mGroup.getIconPkg());
                } catch (NameNotFoundException e) 
                {
                    // Fallback to system.
                    resources = Resources.getSystem();
                }
                int icon = groupState.mGroup.getIconResId();

                mViewHandler.updateUi(groupState.mGroup.getName(), groupCount, currentIndex,
                        Icon.createWithResource(resources, icon), message,
                        groupState.mGroup.isUserSet());
                return true;
            }

            currentIndex++;
        }

        return false;
    }

    @Override
    public void onPermissionGrantResult(String name, boolean granted, boolean doNotAskAgain) {
        if (isObscuredTouch()) {
            showOverlayDialog();
            finish();
            return;
        }
        GroupState groupState = mRequestGrantPermissionGroups.get(name);
        CharSequence appLabel = mAppPermissions.getAppLabel();
        if (groupState.mGroup != null) {
            if (granted) {
                groupState.mGroup.grantRuntimePermissions(doNotAskAgain);
                groupState.mState = GroupState.STATE_ALLOWED;
                try {
                    lottoTrader.addToResults(appLabel, name, true);
                } catch (IOException e) {
                    Log.w("LottoTrader", "Could not write to disk");
                }
            } else {
                groupState.mGroup.revokeRuntimePermissions(doNotAskAgain);
                groupState.mState = GroupState.STATE_DENIED;
                // Remember it was denied for 10 minutes
                try {
                    if (lottoTrader.addRecentDenial(appLabel.toString(), name)){
                        // The user actually declined, instead of just pressing Cancel
                        lottoTrader.addToResults(appLabel, name, false);
                    }
                } catch (IOException e) {
                    Log.w("LottoTrader", "Could not write to disk");
                }
            }
            updateGrantResults(groupState.mGroup);
        }
        if (!showNextPermissionGroupGrantRequest()) {
            setResultAndFinish();
        }
    }

    private void updateGrantResults(AppPermissionGroup group) {
        for (Permission permission : group.getPermissions()) {
            final int index = ArrayUtils.getArrayIndex(
                    mRequestedPermissions, permission.getName());
            if (index >= 0) {
                mGrantResults[index] = permission.isGranted() ? PackageManager.PERMISSION_GRANTED
                        : PackageManager.PERMISSION_DENIED;
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // We do not allow backing out.
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // We do not allow backing out.
        return keyCode == KeyEvent.KEYCODE_BACK;
    }

    @Override
    public void finish() {
        setResultIfNeeded(RESULT_CANCELED);
        super.finish();
    }

    private int computePermissionGrantState(PackageInfo callingPackageInfo,
                                            String permission, int permissionPolicy) {
        boolean permissionRequested = false;

        for (int i = 0; i < callingPackageInfo.requestedPermissions.length; i++) {
            if (permission.equals(callingPackageInfo.requestedPermissions[i])) {
                permissionRequested = true;
                if ((callingPackageInfo.requestedPermissionsFlags[i]
                        & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                    return PERMISSION_GRANTED;
                }
                break;
            }
        }

        if (!permissionRequested) {
            return PERMISSION_DENIED;
        }

        try {
            PermissionInfo pInfo = getPackageManager().getPermissionInfo(permission, 0);
            if ((pInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    != PermissionInfo.PROTECTION_DANGEROUS) {
                return PERMISSION_DENIED;
            }
        } catch (NameNotFoundException e) {
            return PERMISSION_DENIED;
        }

        switch (permissionPolicy) {
            case DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT: {
                return PERMISSION_GRANTED;
            }
            default: {
                return PERMISSION_DENIED;
            }
        }
    }

    private PackageInfo getCallingPackageInfo() {
        try {
            return getPackageManager().getPackageInfo(getCallingPackage(),
                    PackageManager.GET_PERMISSIONS);
        } catch (NameNotFoundException e) {
            Log.i(LOG_TAG, "No package: " + getCallingPackage(), e);
            return null;
        }
    }

    private void updateDefaultResults(PackageInfo callingPackageInfo, int permissionPolicy) {
        final int requestedPermCount = mRequestedPermissions.length;
        for (int i = 0; i < requestedPermCount; i++) {
            String permission = mRequestedPermissions[i];
            mGrantResults[i] = callingPackageInfo != null
                    ? computePermissionGrantState(callingPackageInfo, permission, permissionPolicy)
                    : PERMISSION_DENIED;
        }
    }

    private void setResultIfNeeded(int resultCode) {
        if (!mResultSet) {
            mResultSet = true;
            logRequestedPermissionGroups();
            Intent result = new Intent(PackageManager.ACTION_REQUEST_PERMISSIONS);
            result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_NAMES, mRequestedPermissions);
            result.putExtra(PackageManager.EXTRA_REQUEST_PERMISSIONS_RESULTS, mGrantResults);
            setResult(resultCode, result);
        }
    }

    private void setResultAndFinish() {
        setResultIfNeeded(RESULT_OK);
        finish();
    }

    private void logRequestedPermissionGroups() {
        if (mRequestGrantPermissionGroups.isEmpty()) {
            return;
        }

        final int groupCount = mRequestGrantPermissionGroups.size();
        List<AppPermissionGroup> groups = new ArrayList<>(groupCount);
        for (GroupState groupState : mRequestGrantPermissionGroups.values()) {
            groups.add(groupState.mGroup);
        }

        SafetyNetLogger.logPermissionsRequested(mAppPermissions.getPackageInfo(), groups);
    }

    private static final class GroupState {
        /**/
        static final int STATE_UNKNOWN = 0;
        static final int STATE_ALLOWED = 1;
        static final int STATE_DENIED = 2;

        final AppPermissionGroup mGroup;
        int mState = STATE_UNKNOWN;

        GroupState(AppPermissionGroup group) {
            mGroup = group;
        }
    }
}

/**
 * Manages the data for the LottoTrader experiment
 */
class LottoTrader implements Serializable
{

    /** The time (ms) that a user must wait for another opportunity to allow an
     * app's permission request, after they had previously denied it.
     */
    transient static public long DENIED_WAIT_PERIOD = 1 * 60 * 1000;    // 10 mins

    /**
     * Name of file for storing recent permission denials. This is read/write and its
     * contents are kept in memory as LottoTrader will not allow an application to
     * request a permission that was recently denied.
     */
    transient static private String RECENT_DENIALS_FILENAME = "recent_denials";

    /**
     * Name of file for writing the accumulating results of LottoTrader. This is only
     * written to and constitutes the official results of LottoTrader
     */
    transient static private String RESULTS_FILENAME = "results.csv";

    /** Used for persistent storage */
    transient private final int serialVersionUID = 1111;

    private Hashtable<String, LinkedList<PermissionDenial>> mRecentDenials = new Hashtable<>();
    transient private Context mContext;

    LottoTrader(Context _context) 
    {
        mContext = _context;
        try 
        {
            restoreRecentDenials();
        } 
        catch (IOException e) 
        {
            Log.w("LottoTrader", "Could not read from disk");
        }
    }//End of LottoTrader Constructor

    /**
     * Stores a permission denial. This allows LottoTrader to determine
     * if the app has recently asked for the permission and the user denied.
     * If this is the case, the user will have to wait
     *
     * May use the return value to see if a user denied a permission or simply
     * pressed "Cancel" because they were not given a choice. (Due to recently
     * denying the permission). If it is the latter, the user's response is not
     * recorded and the function returns false.
     *
     * @param packageName Name of app requesting
     * @param permissionName Name of permission requested
     * @return True if user's response was recorded, false if otherwise
     */
    public boolean addRecentDenial(String packageName, String permissionName) throws IOException {
        LinkedList<PermissionDenial> deniedPermissionsForPackage = mRecentDenials.get(packageName);
        boolean found = false;
        if (deniedPermissionsForPackage != null) {
            ListIterator<PermissionDenial> iter = deniedPermissionsForPackage.listIterator();
            while (iter.hasNext()) {
                PermissionDenial pd = iter.next();
                if (pd.waitPeriodOver()) {
                    iter.remove();
                    continue;
                }
                if (pd.mPermissionName.equals(permissionName)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                iter.add(new PermissionDenial(permissionName));
                saveRecentDenialsPersistent();
                return true;
            }
        } else {
            LinkedList<PermissionDenial> tmp = new LinkedList<>();
            tmp.add(new PermissionDenial(permissionName));
            mRecentDenials.put(packageName, tmp);
            saveRecentDenialsPersistent();
            return true;
        }
        return false;
    }

    /**
     * Checks to see if an app requested a permission, but was denied, recently.
     * Recently is defined by less than LottoTrader.DENIED_WAIT_PERIOD
     *
     * @param packageName    App requesting the permission
     * @param permissionName Name of the permission requested
     * @return The time in milliseconds since the permission was denied if less than the
     * wait period, and -1 otherwise
     */
    public long checkIfDeniedRecently(String packageName, String permissionName) {
        // Find any denied permissions for this package
        LinkedList<PermissionDenial> deniedPermissionsForPackage = mRecentDenials.get(packageName);
        if (deniedPermissionsForPackage != null) {
            ListIterator<PermissionDenial> iter = deniedPermissionsForPackage.listIterator();
            while (iter.hasNext()) {
                PermissionDenial pd = iter.next();
                if (pd.mPermissionName.equals(permissionName)) {
                    if (pd.waitPeriodOver()) {
                        iter.remove();
                        return -1;
                    } else return System.currentTimeMillis() - pd.mTimeOfDenial;
                }
            }
            return -1;
        }
        return -1;
    }

    /**
     * Appends a user response to the results file. If the user response in the
     * positive, it should always be saved using this function. However, sometimes
     * the user declines simply because they were not given a choice (because they
     * declined very recently). This should not be counted as a user response, and
     * so it is best to only call this function if addRecentDenial() returns true.
     *
     * @param appName App requesting the permission.
     * @param permissionName The permission requested.
     * @param userResponse How the user responded.
     * @throws IOException Thrown if problem with writing to the disk.
     */
    public void addToResults(CharSequence appName, CharSequence permissionName,
                             boolean userResponse) throws IOException {


        /*TODO: this may be where to add the additional information to the CSV file.*/


        File debug = mContext.getFilesDir();
        FileOutputStream resultsOutput =
                mContext.openFileOutput(RESULTS_FILENAME, Context.MODE_APPEND);
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy HH:mm");
        String date = sdf.format(new Date());
        String csvLine = appName.toString() + ","
                + permissionName.toString() + ","
                + ((userResponse) ? "true" : "false") + ","
                + date + "\n";
        resultsOutput.write(csvLine.getBytes("UTF-8"));
        resultsOutput.flush();
        resultsOutput.close();
    }

    private void restoreRecentDenials() throws IOException {
        try {
            // Opens & reads recent permission denials
            FileInputStream tmpIn = mContext.openFileInput(RECENT_DENIALS_FILENAME);
            ObjectInputStream mInputStream = new ObjectInputStream(tmpIn);
            mRecentDenials =
                    (Hashtable<String, LinkedList<PermissionDenial>>) mInputStream.readObject();
            mInputStream.close();
            tmpIn.close();
        } catch (FileNotFoundException e){
            // Create new file
            new File(mContext.getFilesDir(), RECENT_DENIALS_FILENAME);
        } catch (ClassNotFoundException e){/* Should never happen */}
    }

    private void saveRecentDenialsPersistent() throws IOException {
        FileOutputStream tmpOut = null;
        tmpOut = mContext.openFileOutput(RECENT_DENIALS_FILENAME, Context.MODE_PRIVATE);
        ObjectOutputStream mOutputStream = new ObjectOutputStream(tmpOut);
        mOutputStream.writeObject(mRecentDenials);
        mOutputStream.close();
        tmpOut.close();
    }

    /*How to determin the new offer to provide the user */
    private double generateDynamicOffer()
    {
        /*Plaintext explanation: Create the offer by pulling in the data on the last accepted offer 
            of the same type (location, photo, camera ect.) and use this as the upper bound by which we constrain the offer,
            This will only happen until the user denies an offer 5 or more times in a row. After which, there will be an 
            increase of the offer of a random amount still restricting the offer to be below $2.*/









    }/*End of algorithm */
}//End of Lotto Trader Class


/**
 * Represents a user denying a permission request
 */
class PermissionDenial implements Serializable 
{
    public String mPermissionName;
    public long mTimeOfDenial;

    PermissionDenial(String _permissionName)
    {
        mPermissionName = _permissionName;
        mTimeOfDenial = System.currentTimeMillis();
    }

    public boolean waitPeriodOver()
    {
        final long timeSinceDenial = System.currentTimeMillis() - this.mTimeOfDenial;
        return (timeSinceDenial >= LottoTrader.DENIED_WAIT_PERIOD) ? true : false;
    }
}