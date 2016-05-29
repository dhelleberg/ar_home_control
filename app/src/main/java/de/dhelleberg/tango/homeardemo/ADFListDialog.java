package de.dhelleberg.tango.homeardemo;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.View;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoAreaDescriptionMetaData;
import com.google.atap.tangoservice.TangoErrorException;

import java.util.ArrayList;


public class ADFListDialog  implements MaterialDialog.ListCallback, MaterialDialog.SingleButtonCallback {

    private static final String TAG = "ADFListDialog";
    private final CallbackListener listener;

    private ArrayList<String> areaDescriptionTitles;
    private ArrayList<String> adfUUIDs;

    protected final MaterialDialog.Builder dialogBuilder;
    protected final Context context;
    protected final Tango tango;
    protected MaterialDialog dialog;


    public ADFListDialog(Context context, Tango tango, CallbackListener listener) {
        this.context = context;
        this.tango = tango;
        this.dialogBuilder = new MaterialDialog.Builder(context);

        this.listener = listener;
        fillAreaDescriptions();
        dialogBuilder.title(R.string.adf_list_title);
        if (adfUUIDs.size() > 0) {
            dialogBuilder.positiveText(R.string.OK);
            dialogBuilder.itemsCallback(this);
            dialogBuilder.items(areaDescriptionTitles);
        } else {
            dialogBuilder.content(R.string.no_adfs).positiveText(R.string.OK);
        }
    }

    private void fillAreaDescriptions() {
        try {
            adfUUIDs = tango.listAreaDescriptions();
            areaDescriptionTitles = new ArrayList<>();
            for (String uuid : adfUUIDs) {
                TangoAreaDescriptionMetaData metaData = tango.loadAreaDescriptionMetaData(uuid);
                byte[] name = metaData.get(TangoAreaDescriptionMetaData.KEY_NAME);
                areaDescriptionTitles.add(new String(name));
            }
        } catch (TangoErrorException e) {
            Log.e(TAG, "fillAreaDescriptions: " + e.getMessage(), e);
        }
    }

    @Override
    public void onSelection(MaterialDialog dialog, View itemView, int which, CharSequence text) {
        String uuid = adfUUIDs.get(which);
        listener.selectedADF(uuid);
    }

    @Override
    public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
        listener.selectedADF(null);
    }
    public void show() {
        dialog = dialogBuilder.build();
        dialog.show();
    }

    public interface CallbackListener {
        void selectedADF(String uuid);
    }
}
