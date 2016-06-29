package com.odoo.core.account.setup;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.odoo.base.addons.ir.IrModule;
import com.odoo.core.account.setup.utils.Priority;
import com.odoo.core.account.setup.utils.SetupUtils;
import com.odoo.core.orm.ODataRow;
import com.odoo.core.orm.OModel;
import com.odoo.core.service.OSyncAdapter;
import com.odoo.core.support.OUser;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import odoo.Odoo;

/**
 * Setup Service
 * 1. Syncing for HIGH priority models
 * 2. Getting user groups and model access based on group
 * 3. Syncing models with DEFAULT priority (Such as reference models)
 * 5. TODO: What next ?
 */
public class SetupIntentService extends IntentService {

    public static final String TAG = SetupIntentService.class.getCanonicalName();
    public static final String ACTION_SETUP_RESPONSE = "setup_response";
    public static final String EXTRA_PROGRESS = "extra_progress_value";
    public static final String EXTRA_ERROR = "extra_error_response";
    public static final String KEY_DEPENDENCY_ERROR = "module_dependency_error";
    public static final String KEY_MODULES = "modules";
    public static final String KEY_SKIP_MODULE_CHECK = "skip_module_check";
    public static final String KEY_SETUP_FINISHED = "setup_finished";
    private Odoo odoo;
    private SetupUtils setupUtils;
    private OUser user;
    private int totalFinishedTasks = 0;
    private int totalTasks = 4;

    public SetupIntentService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Bundle extra = intent.getExtras();

        user = OUser.current(getApplicationContext()); // FIXME: can be also request form sync
        Log.d(TAG, "Setup Started for User : " + user.getAndroidName());
        setupUtils = new SetupUtils(getApplicationContext(), user);
        odoo = OSyncAdapter.createOdooInstance(getApplicationContext(), user);
        if (odoo == null) {
            Log.e(TAG, "Unable to create odoo instance for user : " + user.getAndroidName());
            return;
        }
        HashMap<Priority, List<Class<? extends OModel>>> setupModels = setupUtils.getSetupModels();

        // Syncing high priority models first (The Base data)
        Log.v(TAG, "Processing HIGH priority models");
        syncModels(setupModels.get(Priority.HIGH));

        if (!extra.containsKey(KEY_SKIP_MODULE_CHECK) && !checkModuleDependency()) {
            return;
        }

        // Syncing user groups and model access rights (Access rights and res groups data)
        Log.v(TAG, "Processing access rights models");
        syncModels(setupModels.get(Priority.MEDIUM));

        // Syncing xml id data for models
        syncModels(setupModels.get(Priority.LOW));

        // Master records for each model references
        Log.v(TAG, "Processing master record models for each model");
        syncModels(setupModels.get(Priority.DEFAULT));

        Log.v(TAG, "All set. Setup service finished.");
        Bundle data = new Bundle();
        data.putBoolean(KEY_SETUP_FINISHED, true);
        pushUpdate(data);
    }

    private void syncModels(List<Class<? extends OModel>> models) {
        for (Class<? extends OModel> modelCls : models) {
            try {
                Constructor constructor = modelCls.getConstructor(Context.class, OUser.class);
                OModel model = (OModel) constructor.newInstance(getApplicationContext(), user);
                SyncResult result = syncData(model);
                //TODO: Deal with result B)
            } catch (Exception e) {
                Log.e(TAG, "Model object create fail: " + e.getMessage(), e);
            }
        }
        totalFinishedTasks++;
        Bundle extra = new Bundle();
        extra.putInt(EXTRA_PROGRESS, (totalFinishedTasks * 100) / totalTasks);
        pushUpdate(extra);
    }

    private SyncResult syncData(OModel model) {
        SyncResult result = new SyncResult();
        OSyncAdapter syncAdapter = new OSyncAdapter(getApplicationContext(), model.getClass(), null, true);
        syncAdapter.setModelLogOnly(true);
        syncAdapter.setModel(model);
        syncAdapter.checkForCreateDate(false);
        syncAdapter.onPerformSync(user.getAccount(), null, model.authority(), null, result);
        return result;
    }


    private void pushUpdate(Bundle extra) {
        extra = extra != null ? extra : Bundle.EMPTY;
        Intent intent = new Intent(ACTION_SETUP_RESPONSE);
        intent.putExtras(extra);
        LocalBroadcastManager.getInstance(getApplicationContext())
                .sendBroadcast(intent);
    }

    private void pushError(Bundle extra) {
        Intent data = new Intent(ACTION_SETUP_RESPONSE);
        data.putExtras(extra);
        LocalBroadcastManager.getInstance(getApplicationContext())
                .sendBroadcast(data);
    }

    private boolean checkModuleDependency() {
        IrModule module = new IrModule(getApplicationContext(), user);
        List<String> modulesNotInstalled = new ArrayList<>();
        for (ODataRow row : module.select()) {
            if (!row.getString("state").equals("installed")) {
                Log.e(TAG, "Dependency module not installed on server : " + row.getString("shortdesc"));
                modulesNotInstalled.add(row.getString("shortdesc"));
            }
        }
        if (!modulesNotInstalled.isEmpty()) {
            Bundle data = new Bundle();
            data.putString(EXTRA_ERROR, KEY_DEPENDENCY_ERROR);
            data.putStringArray(KEY_MODULES, modulesNotInstalled.toArray(new String[modulesNotInstalled.size()]));
            pushError(data);
            return false;
        }
        return true;
    }

}