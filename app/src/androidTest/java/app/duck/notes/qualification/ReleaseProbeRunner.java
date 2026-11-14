package app.duck.notes.qualification;

import android.app.Application;
import android.content.Context;
import androidx.test.runner.AndroidJUnitRunner;

/** Read-only inspection after the real app has run; suppress startup writers during inspection. */
public final class ReleaseProbeRunner extends AndroidJUnitRunner {
    @Override public Application newApplication(ClassLoader loader, String name, Context context)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return super.newApplication(loader, Application.class.getName(), context);
    }
}
