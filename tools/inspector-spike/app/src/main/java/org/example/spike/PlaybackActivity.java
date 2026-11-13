package org.example.spike;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** Disposable measurement harness for generated media, not an application player. */
@UnstableApi
public final class PlaybackActivity extends Activity {
  private static final String[] FILES = {
      "chapters.mp3", "chapters-vbr.mp3", "chapters-quicktime.m4b",
      "no-chapters.wav", "no-chapters.flac", "no-chapters.ogg", "no-chapters-opus.ogg"
  };
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final StringBuilder report = new StringBuilder("Playback spike\n");
  private TextView view;
  private ExoPlayer player;
  private int index;
  private int phase;
  private long deadline;
  private long startWall;
  private long startPosition;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    view = new TextView(this);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(view);
    setContentView(scroll);
    view.setText("Copying synthetic files...");
    new Thread(() -> {
      try {
        for (String name : FILES) {
          try (InputStream in = getAssets().open(name);
               FileOutputStream out = new FileOutputStream(new File(getCacheDir(), name))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
          }
        }
        runOnUiThread(this::startMeasurements);
      } catch (Exception e) {
        runOnUiThread(() -> view.setText("Fixture copy failed: " + e.getClass().getSimpleName()));
      }
    }).start();
  }

  private void startMeasurements() {
    if (isFinishing() || isDestroyed()) return;
    player = new ExoPlayer.Builder(this).build();
    player.setAudioAttributes(new AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true);
    player.setVolume(0f);
    nextFile();
  }

  private void nextFile() {
    if (index >= FILES.length) {
      player.release();
      player = null;
      report.append("Done. Position is player-reported; decoded seek accuracy and pitch were not measured.\n");
      view.setText(report.toString());
      return;
    }
    report.append(FILES[index]).append("\n");
    player.setPlaybackSpeed(1f);
    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(new File(getCacheDir(), FILES[index]))));
    player.prepare();
    phase = 0;
    deadline = SystemClock.elapsedRealtime() + 15000;
    handler.post(this::tick);
  }

  private void tick() {
    if (player == null) return;
    long now = SystemClock.elapsedRealtime();
    if (player.getPlayerError() != null || now > deadline) {
      report.append("failed: ").append(player.getPlayerError() != null
          ? player.getPlayerError().getErrorCodeName() : "timeout").append("\n\n");
      player.stop();
      index++;
      nextFile();
      return;
    }
    switch (phase) {
      case 0:
        if (player.getPlaybackState() == Player.STATE_READY) {
          report.append("durationMs=").append(player.getDuration()).append("\n");
          player.seekTo(3000);
          phase = 1;
        }
        break;
      case 1:
        if (!player.isLoading() && player.getPlaybackState() == Player.STATE_READY) {
          report.append("seek requested=3000 reported=").append(player.getCurrentPosition()).append("\n");
          player.play();
          phase = 2;
        }
        break;
      case 2:
        if (player.isPlaying()) {
          startWall = now;
          phase = 3;
        }
        break;
      case 3:
        if (now - startWall >= 800) {
          startWall = now;
          startPosition = player.getCurrentPosition();
          phase = 4;
        }
        break;
      case 4:
        if (now - startWall >= 1000) {
          report.append("1x wallMs=").append(now - startWall).append(" mediaMs=")
              .append(player.getCurrentPosition() - startPosition).append("\n");
          player.setPlaybackSpeed(2f);
          startWall = now;
          phase = 5;
        }
        break;
      case 5:
        if (now - startWall >= 600) {
          startWall = now;
          startPosition = player.getCurrentPosition();
          phase = 6;
        }
        break;
      case 6:
        if (now - startWall >= 1000) {
          report.append("2x wallMs=").append(now - startWall).append(" mediaMs=")
              .append(player.getCurrentPosition() - startPosition).append("\n\n");
          player.pause();
          index++;
          view.setText(report.toString());
          nextFile();
          return;
        }
        break;
      default: throw new IllegalStateException("unexpected phase");
    }
    view.setText(report.toString());
    handler.postDelayed(this::tick, 50);
  }

  @Override protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (player != null) {
      player.release();
      player = null;
    }
    super.onDestroy();
  }
}
