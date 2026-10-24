package com.oso.xmbhome;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

/**
 * What is playing in the background, without notification access:
 * Spotify's session (through its media library service) or its "Device Broadcast Status"
 * broadcasts give the song and artist; for anything else
 * Android only says that audio is playing, so the source is guessed as the last app this launcher
 * opened. Both are listened to only while the launcher is started.
 */
final class NowPlaying {
    static final String SPOTIFY = "com.spotify.tv.android";
    private static final String TAG = "XmbNowPlaying";
    private static final String SPOTIFY_SERVICE = "com.spotify.androidtv.medialibraryservice.AndroidTvMediaLibraryService";

    interface Listener { void onNowPlaying(String pkg, String text); }

    private final Context context;
    private final Listener listener;
    private final AudioManager audio;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String track, artist;
    private boolean spotifyPlaying, registered, spotifyRefused;
    private final boolean spotifyInstalled;
    private MediaBrowser browser;
    private MediaController controller;

    private final BroadcastReceiver spotify = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i.getAction() == null) return;
            if (i.getAction().endsWith("metadatachanged")) {
                track = i.getStringExtra("track");
                artist = i.getStringExtra("artist");
            } else if (i.getAction().endsWith("playbackstatechanged")) {
                spotifyPlaying = i.getBooleanExtra("playing", false);
            }
            publish();
        }
    };

    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            publish();
        }
    };

    NowPlaying(Context c, Listener l) {
        context = c;
        listener = l;
        audio = c.getSystemService(AudioManager.class);
        prefs = c.getSharedPreferences("xmb", Context.MODE_PRIVATE);
        boolean installed;
        try {
            c.getPackageManager().getPackageInfo(SPOTIFY, 0);
            installed = true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            installed = false;
        }
        spotifyInstalled = installed;
    }

    /** Remember the last app opened from the launcher: the best guess for "Now Playing...". */
    void launched(String pkg) {
        if (pkg != null) prefs.edit().putString("last_app", pkg).apply();
    }

    /**
     * Spotify's TV app sends no broadcasts, but it exposes a media library service; connecting to
     * it as a browser hands us its session, whose metadata has the song and artist. Connected only
     * while audio plays and the launcher is started, so it never wakes Spotify by itself.
     */
    private void connectSpotify() {
        if (browser != null || spotifyRefused) return;
        browser = new MediaBrowser(context, new ComponentName(SPOTIFY, SPOTIFY_SERVICE), new MediaBrowser.ConnectionCallback() {
            @Override
            public void onConnected() {
                Log.i(TAG, "Spotify session connected");
                if (browser == null) return;
                controller = new MediaController(context, browser.getSessionToken());
                controller.registerCallback(session, main);
                readSession();
            }

            @Override
            public void onConnectionFailed() {
                Log.i(TAG, "Spotify refused the media browser connection");
                spotifyRefused = true;   // don't keep knocking until the next start
                disconnectSpotify();
            }

            @Override
            public void onConnectionSuspended() {
                disconnectSpotify();
            }
        }, null);
        Log.i(TAG, "connecting to Spotify's media library service");
        try {
            browser.connect();
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "can't bind Spotify's media library service", e);
            spotifyRefused = true;
            browser = null;
        }
    }

    private void disconnectSpotify() {
        if (controller != null) controller.unregisterCallback(session);
        controller = null;
        if (browser != null) {
            try { browser.disconnect(); } catch (IllegalStateException ignored) { }
        }
        browser = null;
    }

    private final MediaController.Callback session = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { readSession(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { readSession(); }
        @Override public void onSessionDestroyed() { disconnectSpotify(); publish(); }
    };

    private void readSession() {
        if (controller == null) return;
        MediaMetadata m = controller.getMetadata();
        PlaybackState st = controller.getPlaybackState();
        if (m != null) {
            track = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        }
        spotifyPlaying = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        Log.i(TAG, "Spotify session: state=" + (st == null ? null : st.getState()) + " hasTitle=" + !TextUtils.isEmpty(track));
        publish();
    }

    void start() {
        if (registered) return;
        spotifyRefused = false;
        registered = true;
        IntentFilter f = new IntentFilter();
        f.addAction("com.spotify.music.metadatachanged");
        f.addAction("com.spotify.music.playbackstatechanged");
        context.registerReceiver(spotify, f, Context.RECEIVER_EXPORTED);
        audio.registerAudioPlaybackCallback(playback, main);
        publish();
    }

    void stop() {
        if (!registered) return;
        registered = false;
        context.unregisterReceiver(spotify);
        audio.unregisterAudioPlaybackCallback(playback);
        disconnectSpotify();
    }

    private void publish() {
        boolean playing = audio.isMusicActive();
        if (playing && registered && spotifyInstalled) connectSpotify();
        if (!playing) {
            listener.onNowPlaying(null, null);
        } else if (spotifyPlaying && !TextUtils.isEmpty(track)) {
            listener.onNowPlaying(SPOTIFY, TextUtils.isEmpty(artist) ? track : track + " — " + artist);
        } else {
            listener.onNowPlaying(prefs.getString("last_app", null), "Now Playing...");
        }
    }
}
