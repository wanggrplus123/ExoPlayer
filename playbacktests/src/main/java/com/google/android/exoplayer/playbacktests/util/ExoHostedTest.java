/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.google.android.exoplayer.playbacktests.util;

import com.google.android.exoplayer.CodecCounters;
import com.google.android.exoplayer.DefaultTrackSelectionPolicy;
import com.google.android.exoplayer.DefaultTrackSelector;
import com.google.android.exoplayer.ExoPlaybackException;
import com.google.android.exoplayer.ExoPlayer;
import com.google.android.exoplayer.ExoPlayerFactory;
import com.google.android.exoplayer.Format;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SimpleExoPlayer;
import com.google.android.exoplayer.TrackSelectionPolicy;
import com.google.android.exoplayer.audio.AudioTrack;
import com.google.android.exoplayer.playbacktests.util.HostActivity.HostedTest;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DataSourceFactory;
import com.google.android.exoplayer.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer.util.Util;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import junit.framework.Assert;

/**
 * A {@link HostedTest} for {@link ExoPlayer} playback tests.
 */
public abstract class ExoHostedTest implements HostedTest, ExoPlayer.EventListener,
    SimpleExoPlayer.DebugListener {

  static {
    // ExoPlayer's AudioTrack class is able to work around spurious timestamps reported by the
    // platform (by ignoring them). Disable this workaround, since we're interested in testing
    // that the underlying platform is behaving correctly.
    AudioTrack.failOnSpuriousAudioTimestamp = true;
  }

  public static final long MAX_PLAYING_TIME_DISCREPANCY_MS = 2000;

  private final String tag;
  private final boolean fullPlaybackNoSeeking;
  private final CodecCounters videoCodecCounters;
  private final CodecCounters audioCodecCounters;

  private ActionSchedule pendingSchedule;
  private Handler actionHandler;
  private DefaultTrackSelector trackSelector;
  private SimpleExoPlayer player;
  private ExoPlaybackException playerError;
  private boolean playerWasPrepared;
  private boolean playerFinished;
  private boolean playing;
  private long totalPlayingTimeMs;
  private long lastPlayingStartTimeMs;
  private long sourceDurationMs;

  /**
   * @param tag A tag to use for logging.
   * @param fullPlaybackNoSeeking Whether the test will play the target media in full without
   *     seeking. If set to true, the test will assert that the total time spent playing the media
   *     was within {@link #MAX_PLAYING_TIME_DISCREPANCY_MS} of the media duration.
   */
  public ExoHostedTest(String tag, boolean fullPlaybackNoSeeking) {
    this.tag = tag;
    this.fullPlaybackNoSeeking = fullPlaybackNoSeeking;
    videoCodecCounters = new CodecCounters();
    audioCodecCounters = new CodecCounters();
  }

  /**
   * Sets a schedule to be applied during the test.
   *
   * @param schedule The schedule.
   */
  public final void setSchedule(ActionSchedule schedule) {
    if (player == null) {
      pendingSchedule = schedule;
    } else {
      schedule.start(player, trackSelector, actionHandler);
    }
  }

  // HostedTest implementation

  @Override
  public final void onStart(HostActivity host, Surface surface) {
    // Build the player.
    TrackSelectionPolicy trackSelectionPolicy = buildTrackSelectionPolicy(host);
    trackSelector = new DefaultTrackSelector(trackSelectionPolicy, null);
    player = buildExoPlayer(host, surface, trackSelector);
    DataSourceFactory dataSourceFactory = new DefaultDataSourceFactory(host, Util
        .getUserAgent(host, "ExoPlayerPlaybackTests"));
    player.setSource(buildSource(host, dataSourceFactory, player.getBandwidthMeter()));
    player.addListener(this);
    player.setDebugListener(this);
    player.setPlayWhenReady(true);
    actionHandler = new Handler();
    // Schedule any pending actions.
    if (pendingSchedule != null) {
      pendingSchedule.start(player, trackSelector, actionHandler);
      pendingSchedule = null;
    }
  }

  @Override
  public final void onStop() {
    actionHandler.removeCallbacksAndMessages(null);
    sourceDurationMs = player.getDuration();
    player.release();
    player = null;
  }

  @Override
  public final boolean isFinished() {
    return playerFinished;
  }

  @Override
  public final void onFinished() {
    if (playerError != null) {
      throw new Error(playerError);
    }
    logMetrics(audioCodecCounters, videoCodecCounters);
    if (fullPlaybackNoSeeking) {
      // Assert that the playback spanned the correct duration of time.
      long minAllowedActualPlayingTimeMs = sourceDurationMs - MAX_PLAYING_TIME_DISCREPANCY_MS;
      long maxAllowedActualPlayingTimeMs = sourceDurationMs + MAX_PLAYING_TIME_DISCREPANCY_MS;
      Assert.assertTrue("Total playing time: " + totalPlayingTimeMs + ". Actual media duration: "
          + sourceDurationMs, minAllowedActualPlayingTimeMs <= totalPlayingTimeMs
          && totalPlayingTimeMs <= maxAllowedActualPlayingTimeMs);
    }
    // Make any additional assertions.
    assertPassed(audioCodecCounters, videoCodecCounters);
  }

  // ExoPlayer.Listener

  @Override
  public final void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
    Log.d(tag, "state [" + playWhenReady + ", " + playbackState + "]");
    playerWasPrepared |= playbackState != ExoPlayer.STATE_IDLE;
    if (playbackState == ExoPlayer.STATE_ENDED
        || (playbackState == ExoPlayer.STATE_IDLE && playerWasPrepared)) {
      playerFinished = true;
    }
    boolean playing = playWhenReady && playbackState == ExoPlayer.STATE_READY;
    if (!this.playing && playing) {
      lastPlayingStartTimeMs = SystemClock.elapsedRealtime();
    } else if (this.playing && !playing) {
      totalPlayingTimeMs += SystemClock.elapsedRealtime() - lastPlayingStartTimeMs;
    }
    this.playing = playing;
  }

  @Override
  public final void onPlayerError(ExoPlaybackException error) {
    playerWasPrepared = true;
    playerError = error;
    onPlayerErrorInternal(error);
  }

  @Override
  public final void onPlayWhenReadyCommitted() {
    // Do nothing.
  }

  // SimpleExoPlayer.DebugListener

  @Override
  public void onAudioEnabled(CodecCounters counters) {
    Log.d(tag, "audioEnabled");
  }

  @Override
  public void onAudioDecoderInitialized(String decoderName, long elapsedRealtimeMs,
      long initializationDurationMs) {
    Log.d(tag, "audioDecoderInitialized [" + decoderName + "]");
  }

  @Override
  public void onAudioFormatChanged(Format format) {
    Log.d(tag, "audioFormatChanged [" + format.id + "]");
  }

  @Override
  public void onAudioDisabled(CodecCounters counters) {
    Log.d(tag, "audioDisabled");
    audioCodecCounters.merge(counters);
  }

  @Override
  public void onVideoEnabled(CodecCounters counters) {
    Log.d(tag, "videoEnabled");
  }

  @Override
  public void onVideoDecoderInitialized(String decoderName, long elapsedRealtimeMs,
      long initializationDurationMs) {
    Log.d(tag, "videoDecoderInitialized [" + decoderName + "]");
  }

  @Override
  public void onVideoFormatChanged(Format format) {
    Log.d(tag, "videoFormatChanged [" + format.id + "]");
  }

  @Override
  public void onVideoDisabled(CodecCounters counters) {
    Log.d(tag, "videoDisabled");
    videoCodecCounters.merge(counters);
  }

  @Override
  public void onDroppedFrames(int count, long elapsed) {
    Log.d(tag, "droppedFrames [" + count + "]");
  }

  @Override
  public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
    Log.e(tag, "audioTrackUnderrun [" + bufferSize + ", " + bufferSizeMs + ", "
        + elapsedSinceLastFeedMs + "]", null);
  }

  // Internal logic

  @SuppressWarnings("unused")
  protected TrackSelectionPolicy buildTrackSelectionPolicy(HostActivity host) {
    return new DefaultTrackSelectionPolicy();
  }

  @SuppressWarnings("unused")
  protected SimpleExoPlayer buildExoPlayer(HostActivity host, Surface surface,
      DefaultTrackSelector trackSelector) {
    SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(host, trackSelector);
    player.setSurface(surface);
    return player;
  }

  @SuppressWarnings("unused")
  protected abstract SampleSource buildSource(HostActivity host,
      DataSourceFactory dataSourceFactory, BandwidthMeter bandwidthMeter);

  @SuppressWarnings("unused")
  protected void onPlayerErrorInternal(ExoPlaybackException error) {
    // Do nothing. Interested subclasses may override.
  }

  protected void logMetrics(CodecCounters audioCounters, CodecCounters videoCounters) {
    // Do nothing. Subclasses may override to log metrics.
  }

  protected void assertPassed(CodecCounters audioCounters, CodecCounters videoCounters) {
    // Do nothing. Subclasses may override to add additional assertions.
  }

}
