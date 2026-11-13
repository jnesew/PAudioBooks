package org.example.spike;

import android.app.Activity;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.metadata.Chapter;
import androidx.media3.extractor.metadata.id3.ChapterTocFrame;
import androidx.media3.extractor.metadata.id3.ApicFrame;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.inspector.MetadataRetriever;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@UnstableApi
public class MainActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView view = new TextView(this);
    ScrollView scroll = new ScrollView(this);
    scroll.addView(view);
    LinearLayout container = new LinearLayout(this);
    container.setOrientation(LinearLayout.VERTICAL);
    container.setPadding(0, (int) (80 * getResources().getDisplayMetrics().density), 0, 0);
    Button playback = new Button(this);
    playback.setText("Run playback measurements");
    playback.setOnClickListener(v -> startActivity(new Intent(this, PlaybackActivity.class)));
    container.addView(playback);
    container.addView(scroll);
    setContentView(container);
    new Thread(() -> {
      StringBuilder report = new StringBuilder();
      for (String name : new String[]{"chapters.mp3", "chapters-vbr.mp3", "chapters-invalid.mp3", "chapters-nero.m4b", "chapters-both.m4b", "chapters-quicktime.m4b", "chapters-conflict.m4b", "cover.mp3", "cover-large.mp3", "truncated.m4b"}) {
        report.append(name).append("\n");
        try {
          File file = new File(getCacheDir(), name);
          try (InputStream in = getAssets().open(name); FileOutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
          }
          MediaItem item = MediaItem.fromUri(Uri.fromFile(file));
          for (boolean fullSamples : new boolean[]{false, true}) {
            report.append(fullSamples ? "full sample tables:\n" : "default Inspector:\n");
            MetadataRetriever.Builder builder = new MetadataRetriever.Builder(this, item);
            if (fullSamples) {
              DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                  .setMp4ExtractorFlags(Mp4Extractor.FLAG_READ_SEF_DATA);
              builder.setMediaSourceFactory(new DefaultMediaSourceFactory(this, extractors));
            }
            try (MetadataRetriever retriever = builder.build()) {
              long duration = retriever.retrieveDurationUs().get(15, TimeUnit.SECONDS);
              TrackGroupArray tracks = retriever.retrieveTrackGroups().get(15, TimeUnit.SECONDS);
              report.append("durationUs: ").append(duration).append("\n");
              for (int i = 0; i < tracks.length; i++) {
                TrackGroup group = tracks.get(i);
                for (int j = 0; j < group.length; j++) {
                  Metadata metadata = group.getFormat(j).metadata;
                  if (metadata == null) continue;
                  for (int k = 0; k < metadata.length(); k++) {
                    Metadata.Entry entry = metadata.get(k);
                    if (entry instanceof ApicFrame) {
                      ApicFrame picture = (ApicFrame) entry;
                      BitmapFactory.Options options = new BitmapFactory.Options();
                      options.inJustDecodeBounds = true;
                      BitmapFactory.decodeByteArray(picture.pictureData, 0, picture.pictureData.length, options);
                      report.append("artwork type=").append(picture.pictureType)
                          .append(" bytes=").append(picture.pictureData.length)
                          .append(" dimensions=").append(options.outWidth).append("x")
                          .append(options.outHeight).append("\n");
                    } else if (entry instanceof Chapter) {
                      Chapter chapter = (Chapter) entry;
                      report.append("chapter ").append(chapter.getStartTimeMs()).append("–")
                          .append(chapter.getEndTimeMs()).append(" ").append(chapter.getTitle())
                          .append(" hidden=").append(chapter.isHidden()).append("\n");
                    } else if (entry instanceof ChapterTocFrame) {
                      ChapterTocFrame toc = (ChapterTocFrame) entry;
                      report.append("CTOC root=").append(toc.isRoot).append(" ordered=")
                          .append(toc.isOrdered).append(" children=")
                          .append(java.util.Arrays.toString(toc.children)).append("\n");
                    }
                  }
                }
              }
            } catch (Exception e) {
              report.append("error: ").append(e.getClass().getSimpleName()).append("\n");
            }
          }
        } catch (Exception e) { report.append("error: ").append(e.getClass().getSimpleName()).append("\n"); }
        report.append("\n");
      }
      runOnUiThread(() -> view.setText(report.toString()));
    }).start();
  }
}
