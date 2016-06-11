package de.dhelleberg.tango.homeardemo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;

public class StartUpActivity extends Activity {

    private static final String TAG = "StartUpActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_start_up);

        startActivityForResult(Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_ADF_LOAD_SAVE), 0);

    }

    @Override
    protected void onStart() {
        super.onStart();
        SharedPreferences prefs = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        final String adf_uuid = prefs.getString(Constants.PREF_KEY_ADF_UUID, "");
        final Intent intent = new Intent(this, SetupActivity.class);
        if(!adf_uuid.isEmpty())
            intent.putExtra(Constants.EXTRA_KEY_ADF_UUID, adf_uuid);

        Tango tango = new Tango(this);
        /*new ADFListDialog(this, tango, new ADFListDialog.CallbackListener() {
            @Override
            public void selectedADF(String uuid) {
                Log.d(TAG, "selected ADF: "+uuid);
                final Intent intent = new Intent(StartUpActivity.this, SetupActivity.class);
                if(uuid != null) {
                    intent.putExtra(Constants.EXTRA_KEY_ADF_UUID, adf_uuid);
                    startActivity(intent);
                }

            }
        }).show();*/
        startActivity(intent);
    }

    /**
     * Check for ADF permissions
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Check which request we're responding to
        if (requestCode == 0) {
            // Make sure the request was successful
            if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, R.string.arealearning_permission_error, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

}
