package br.uerj.centrocultural.plataformalivre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

public class RadioService extends Service {
    public static final String ACTION_PLAY = "br.uerj.centrocultural.plataformalivre.PLAY";
    public static final String ACTION_STOP = "br.uerj.centrocultural.plataformalivre.STOP";
    public static final String ACTION_SET_VOLUME = "br.uerj.centrocultural.plataformalivre.SET_VOLUME";
    public static final String EXTRA_VOLUME = "volume";

    private static final String STREAM_URL = "https://servidor37-2.brlogic.com:7144/live";
    private static final String CHANNEL_ID = "plataforma_livre_radio";
    private static final int NOTIFICATION_ID = 7144;

    private static volatile boolean active = false;
    private static volatile boolean playing = false;

    private MediaPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private float volume = 0.85f;

    public static boolean isActive() { return active; }
    public static boolean isPlaying() { return playing; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        mediaSession = new MediaSession(this, "PlataformaLivreSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { startFreshStream(volume); }
            @Override public void onStop() { stopPlayback(); }
        });
        mediaSession.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Plataforma Livre")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Centro Cultural UERJ")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Ao Vivo")
                .build());
        mediaSession.setActive(true);
        updatePlaybackState(PlaybackState.STATE_STOPPED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String action = intent.getAction();

        if (ACTION_PLAY.equals(action)) {
            volume = clamp(intent.getFloatExtra(EXTRA_VOLUME, volume));
            startFreshStream(volume);
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else if (ACTION_SET_VOLUME.equals(action)) {
            volume = clamp(intent.getFloatExtra(EXTRA_VOLUME, volume));
            if (player != null) player.setVolume(volume, volume);
        }

        return START_NOT_STICKY;
    }

    private float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private void startFreshStream(float requestedVolume) {
        volume = clamp(requestedVolume);
        releasePlayer();

        active = true;
        playing = false;
        updatePlaybackState(PlaybackState.STATE_CONNECTING);
        startForeground(NOTIFICATION_ID, buildNotification("Conectando ao vivo..."));

        requestAudioFocus();

        try {
            MediaPlayer fresh = new MediaPlayer();
            player = fresh;
            fresh.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            fresh.setVolume(volume, volume);
            fresh.setDataSource(STREAM_URL);

            fresh.setOnPreparedListener(mp -> {
                if (!active || player != mp) return;
                mp.start();
                playing = true;
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateNotification("Ao vivo • Centro Cultural UERJ");
            });

            fresh.setOnErrorListener((mp, what, extra) -> {
                if (player == mp) {
                    active = false;
                    playing = false;
                    releasePlayer();
                    updatePlaybackState(PlaybackState.STATE_ERROR);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
                return true;
            });

            fresh.prepareAsync();
        } catch (Exception e) {
            active = false;
            playing = false;
            releasePlayer();
            updatePlaybackState(PlaybackState.STATE_ERROR);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void stopPlayback() {
        active = false;
        playing = false;
        releasePlayer();
        abandonAudioFocus();
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.reset(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, state == PlaybackState.STATE_PLAYING ? 1f : 0f)
                .build());
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        AudioManager.OnAudioFocusChangeListener listener = focusChange -> {
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                stopPlayback();
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (player != null) player.setVolume(0f, 0f);
            } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (player != null) player.setVolume(volume * 0.2f, volume * 0.2f);
            } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                if (player != null) player.setVolume(volume, volume);
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Plataforma Livre",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Controles da transmissão ao vivo");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 10, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, RadioService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 11, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Action stopAction = new Notification.Action.Builder(
                R.drawable.ic_stop,
                "Parar",
                stopPending
        ).build();

        Notification.MediaStyle style = new Notification.MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Plataforma Livre")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(stopAction)
                .setStyle(style)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override public void onDestroy() {
        active = false;
        playing = false;
        releasePlayer();
        abandonAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
