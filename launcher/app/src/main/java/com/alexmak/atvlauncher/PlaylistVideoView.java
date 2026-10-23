package com.alexmak.atvlauncher;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import java.io.IOException;
import java.util.List;

/**
 * Endless, centre-cropped clip playlist: the player of the NASA video screensaver. Two stacked
 * TextureViews with one MediaPlayer each: while the front clip plays, the next one is prepared on the other view and
 * dissolved in over {@link VideoPlayback#CROSSFADE_MS} before the front one ends. It reports its
 * {@link VideoPlayback.Phase}: any error, or no first frame within {@link VideoPlayback#STARTUP_TIMEOUT_MS}, releases
 * every player and gives up until the next {@link #play}. At most one player is active, two during a crossfade;
 * {@link #stop} releases everything including the surfaces. The owner decides when to play and stop.
 */
final class PlaylistVideoView extends FrameLayout {
    private static final String TAG = "AtvPlaylistVideo";

    interface PhaseListener {
        void onPhase(VideoPlayback.Phase phase);
    }

    /** Maps a clip's URL (as in the playlist) to what MediaPlayer opens: the URL itself or a local file path. */
    interface SourceResolver {
        String sourceFor(String clipUrl);
    }

    /** One TextureView with its MediaPlayer; a slot is either idle, loading, or ready to start. */
    private final class Slot implements TextureView.SurfaceTextureListener {
        final TextureView view;
        MediaPlayer player;
        Surface surface;
        boolean prepared;
        int clip = -1;
        int videoW;
        int videoH;
        Runnable onReady;

        Slot() {
            view = new TextureView(getContext());
            view.setSurfaceTextureListener(this);
            view.setAlpha(0f);
        }

        void load(int clipIndex, Runnable ready) {
            release();
            clip = clipIndex;
            onReady = ready;
            MediaPlayer p = new MediaPlayer();
            player = p;
            String source = resolver.sourceFor(urls.get(clipIndex));
            try {
                p.setDataSource(source);
                if (muted) p.setVolume(0f, 0f);
                p.setOnVideoSizeChangedListener((mp, w, h) -> {
                    videoW = w;
                    videoH = h;
                    applyCrop();
                });
                p.setOnPreparedListener(mp -> {
                    prepared = true;
                    maybeReady();
                });
                p.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onRenderingStart();
                    return true;
                });
                p.setOnErrorListener((mp, what, extra) -> {
                    fail("MediaPlayer error " + what + "/" + extra + " in " + source);
                    return true;
                });
                if (surface != null) p.setSurface(surface);
                p.prepareAsync();
            } catch (IOException | RuntimeException e) {
                fail("cannot load " + source + ": " + e);
            }
        }

        void start() {
            player.start();
        }

        void release() {
            if (player != null) {
                player.release();
                player = null;
            }
            prepared = false;
            onReady = null;
            clip = -1;
            videoW = 0;
            videoH = 0;
            view.setAlpha(0f);
        }

        private void maybeReady() {
            if (player == null || !prepared || surface == null || onReady == null) return;
            Runnable r = onReady;
            onReady = null;
            r.run();
        }

        void applyCrop() {
            if (videoW <= 0 || videoH <= 0 || getWidth() <= 0 || getHeight() <= 0) return;
            float[] s = VideoPlayback.centerCropScale(getWidth(), getHeight(), videoW, videoH);
            view.setScaleX(s[0]);
            view.setScaleY(s[1]);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            surface = new Surface(st);
            if (player != null) player.setSurface(surface);
            maybeReady();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            if (player != null) player.setSurface(null);
            if (surface != null) surface.release();
            surface = null;
            return true;
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    }

    private final SourceResolver resolver;
    private final boolean muted;
    private final PhaseListener listener;
    private final Slot under;
    private final Slot over;
    private List<String> urls;
    private Slot front;
    private Slot back;
    private VideoPlayback.Phase phase = VideoPlayback.Phase.LOADING;
    private long crossfadeAt;
    private boolean running;
    private boolean crossfading;

    private final Runnable prepareNext = this::prepareNextClip;
    private final Runnable crossfade = this::startCrossfade;
    private final Runnable finishCrossfade = () -> {
        crossfading = false;
        back.release();
        scheduleNext();
    };
    private final Runnable startupTimeout = () -> fail("no first frame in " + VideoPlayback.STARTUP_TIMEOUT_MS + " ms");

    PlaylistVideoView(Context context, SourceResolver resolver, boolean muted, PhaseListener listener) {
        super(context);
        this.resolver = resolver;
        this.muted = muted;
        this.listener = listener;
        under = new Slot();
        over = new Slot();
        front = under;
        back = over;
        LayoutParams full = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(under.view, full);
        addView(over.view, full);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed) {
            under.applyCrop();
            over.applyCrop();
        }
    }

    VideoPlayback.Phase phase() {
        return phase;
    }

    /** Starts the playlist from its first clip; the phase restarts at LOADING. */
    void play(List<String> clips) {
        if (clips.isEmpty()) throw new IllegalArgumentException("no clips");
        stopPlayback();
        urls = clips;
        running = true;
        setPhase(VideoPlayback.Phase.LOADING);
        front = under;
        back = over;
        postDelayed(startupTimeout, VideoPlayback.STARTUP_TIMEOUT_MS);
        front.load(0, () -> {
            front.view.setAlpha(1f);
            front.start();
            scheduleNext();
        });
    }

    /** Releases every player and surface; the last phase is kept until the next {@link #play}. */
    void stop() {
        running = false;
        stopPlayback();
    }

    /**
     * Keeps the current clip but stops preparing further ones (the owner is about to replace this view): releases
     * the pre-loaded next player unless a crossfade is in progress.
     */
    void holdCurrent() {
        removeCallbacks(prepareNext);
        removeCallbacks(crossfade);
        if (!crossfading) back.release();
    }

    private void scheduleNext() {
        int duration = front.player.getDuration();
        long position = front.player.getCurrentPosition();
        long now = SystemClock.uptimeMillis();
        crossfadeAt = now + VideoPlayback.crossfadeStartMs(duration) - position;
        long prepareAt = now + VideoPlayback.prepareStartMs(duration) - position;
        postDelayed(prepareNext, Math.max(0, prepareAt - now));
    }

    private void prepareNextClip() {
        back.load(VideoPlayback.nextIndex(front.clip, urls.size()), () -> {
            postDelayed(crossfade, Math.max(0, crossfadeAt - SystemClock.uptimeMillis()));
        });
    }

    private void startCrossfade() {
        Slot outgoing = front;
        Slot incoming = back;
        incoming.start();
        if (incoming == over) {
            incoming.view.animate().alpha(1f).setDuration(VideoPlayback.CROSSFADE_MS).start();
        } else {
            incoming.view.setAlpha(1f);
            outgoing.view.animate().alpha(0f).setDuration(VideoPlayback.CROSSFADE_MS).start();
        }
        front = incoming;
        back = outgoing;
        crossfading = true;
        postDelayed(finishCrossfade, VideoPlayback.CROSSFADE_MS);
    }

    private void stopPlayback() {
        removeCallbacks(startupTimeout);
        removeCallbacks(prepareNext);
        removeCallbacks(crossfade);
        removeCallbacks(finishCrossfade);
        crossfading = false;
        under.view.animate().cancel();
        over.view.animate().cancel();
        under.release();
        over.release();
    }

    private void setPhase(VideoPlayback.Phase next) {
        if (next == phase) return;
        phase = next;
        listener.onPhase(next);
    }

    private void onRenderingStart() {
        if (!running) return;
        removeCallbacks(startupTimeout);
        setPhase(VideoPlayback.nextPhase(phase, VideoPlayback.Event.RENDERING_STARTED));
    }

    /** Any error or timeout: release every player and stay given up until the next {@link #play}. */
    private void fail(String what) {
        Log.w(TAG, what);
        if (!running) return;
        stopPlayback();
        setPhase(VideoPlayback.nextPhase(phase, VideoPlayback.Event.FAILED));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }
}
