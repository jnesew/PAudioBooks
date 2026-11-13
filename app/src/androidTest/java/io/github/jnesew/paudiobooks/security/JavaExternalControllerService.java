package io.github.jnesew.paudiobooks.security;

import android.app.Service;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

/** Runs only in the separately installed test package and uses framework APIs exclusively. */
public final class JavaExternalControllerService extends Service {
    private final Messenger receiver = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        try {
            Bundle args = message.getData();
            args.setClassLoader(MediaSession.Token.class.getClassLoader());
            MediaSession.Token token = args.getParcelable("token");
            MediaController controller = new MediaController(this, token);
            MediaController.TransportControls controls = controller.getTransportControls();
            Bundle forged = new Bundle();
            forged.putString("packageName", "io.github.jnesew.paudiobooks");
            forged.putString("book_id", "unknown-book-id");
            Runnable[] attempts = new Runnable[] {
                controls::play, () -> controls.seekTo(8000),
                () -> controls.playFromUri(Uri.parse("https://invalid.example/fixture"), null),
                () -> controls.playFromUri(Uri.parse("file:///not-a-real-fixture"), null),
                () -> controls.playFromUri(Uri.parse("content://invalid.example/fixture"), null),
                () -> controls.playFromMediaId("unknown-book-id", null),
                () -> controls.sendCustomAction("io.github.jnesew.paudiobooks.LOAD_BOOK", forged)
            };
            int rejected = 0;
            for (Runnable attempt : attempts) {
                try { attempt.run(); } catch (SecurityException failure) { rejected++; }
            }
            result.putInt("attempted", attempts.length);
            result.putInt("rejected", rejected);
        } catch (Exception failure) {
            result.putString("error", failure.getClass().getName());
            result.putBoolean("registrationRejected", failure instanceof SecurityException);
        }
        try { Message reply = Message.obtain(); reply.setData(result); message.replyTo.send(reply); }
        catch (Exception ignored) { }
        return true;
    }));
    @Override public IBinder onBind(Intent intent) { return receiver.getBinder(); }
}
