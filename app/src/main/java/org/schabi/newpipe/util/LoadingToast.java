package org.schabi.newpipe.util;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.schabi.newpipe.R;

public final class LoadingToast {
    private LoadingToast() {
    }

    public static void show(@NonNull final Context context) {
        final Toast toast = new Toast(context);
        final View view = LayoutInflater.from(context).inflate(R.layout.view_loading_toast,
                null, false);
        toast.setView(view);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 180);
        toast.show();
    }
}
