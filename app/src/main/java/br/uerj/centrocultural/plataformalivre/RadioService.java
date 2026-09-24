package br.uerj.centrocultural.plataformalivre;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class RadioService extends Service {
    public static final String ACTION_PLAY = "br.uerj.centrocultural.plataformalivre.PLAY";
    public static final String ACTION_STOP = "br.uerj.centrocultural.plataformalivre.STOP";
    public static final String ACTION_SET_VOLUME = "br.uerj.centrocultural.plataformalivre.SET_VOLUME";
    public static final String ACTION_STATUS = "br.uerj.centrocultural.plataformalivre.STATUS";

    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_ACTIVE = "active";
    public static final String EXTRA_MESSAGE = "message";

    private static final String STREAM_URL = "https://servidor37-2.brlogic.com:7144/live";
    private static final String CHANNEL_ID = "plataforma_livre_radio";
    private static final int NOTIFICATION_ID = 7144;

    private static volatile boolean active = false;
    private static volatile boolean playing = false;
    private static volatile boolean reconnecting = false;

    private MediaPlayer player;
    private MediaSession mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusChangeListener;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable reconnectRunnable;
    private int reconnectAttempt = 0;
    private float volume = 0.85f;

    public static boolean isActive() { return active; }
    public static boolean isPlaying() { return playing; }
    public static boolean isReconnecting() { return reconnecting; }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

        mediaSession = new MediaSession(this, "PlataformaLivreSession");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { startFreshStream(volume); }
            @Override public void onStop() { stopPlayback(); }
        });

        Bitmap art = drawableToBitmap(R.drawable.ic_launcher);
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Plataforma Livre")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Centro Cultural UERJ")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "Rádio ao vivo");
        if (art != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        }
        mediaSession.setMetadata(metadata.build());
        mediaSession.setActive(true);
        updatePlaybackState(PlaybackState.STATE_STOPPED);

        registerNetworkCallback();
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
        active = true;
        playing = false;
        reconnecting = false;
        reconnectAttempt = 0;
        cancelReconnect();
        connectNow("Conectando ao Plataforma Livre...");
    }

    private void connectNow(String message) {
        if (!active) return;

        cancelReconnect();
        releasePlayer();

        playing = false;
        reconnecting = message.toLowerCase().contains("reconect");
        updatePlaybackState(reconnecting ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_CONNECTING);

        startForeground(NOTIFICATION_ID, buildNotification(message));
        broadcastStatus(message);

        if (!hasInternet()) {
            scheduleReconnect("Sem internet. Reconectando ao vivo...");
            return;
        }

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
                reconnecting = false;
                reconnectAttempt = 0;
                updatePlaybackState(PlaybackState.STATE_PLAYING);
                updateNotification("Ao vivo • Centro Cultural UERJ");
                broadcastStatus("Você está ouvindo o Plataforma Livre ao vivo.");
            });

            fresh.setOnErrorListener((mp, what, extra) -> {
                if (active && player == mp) {
                    releasePlayer();
                    scheduleReconnect("Reconectando ao vivo...");
                }
                return true;
            });

            fresh.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            scheduleReconnect("Reconectando ao vivo...");
        }
    }

    private void scheduleReconnect(String message) {
        if (!active) return;

        playing = false;
        reconnecting = true;
        updatePlaybackState(PlaybackState.STATE_BUFFERING);
        updateNotification(message);
        broadcastStatus(message);

        cancelReconnect();
        reconnectAttempt++;
        long delay = Math.min(30000L, 1500L * (1L << Math.min(reconnectAttempt - 1, 4)));

        reconnectRunnable = () -> {
            reconnectRunnable = null;
            if (active) connectNow("Reconectando ao vivo...");
        };
        handler.postDelayed(reconnectRunnable, delay);
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            handler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void stopPlayback() {
        active = false;
        playing = false;
        reconnecting = false;
        reconnectAttempt = 0;
        cancelReconnect();
        releasePlayer();
        abandonAudioFocus();
        updatePlaybackState(PlaybackState.STATE_STOPPED);
        broadcastStatus("Transmissão parada. Ao tocar novamente, o app reconecta ao ponto atual do ao vivo.");
        removeForegroundNotification();
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

    private boolean hasInternet() {
        if (connectivityManager == null) return true;
        Network network = connectivityManager.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void registerNetworkCallback() {
        if (connectivityManager == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (active && !playing) {
                    handler.post(() -> {
                        if (active && !playing) connectNow("Reconectando ao vivo...");
                    });
                }
            }

            @Override public void onLost(Network network) {
                if (active) {
                    handler.post(() -> {
                        if (!active) return;
                        releasePlayer();
                        scheduleReconnect("Sem internet. Reconectando ao vivo...");
                    });
                }
            }
        };

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback);
            } else {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                connectivityManager.registerNetworkCallback(request, networkCallback);
            }
        } catch (Exception ignored) {}
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            try { connectivityManager.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_STOP;
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        state == PlaybackState.STATE_PLAYING ? 1f : 0f)
                .build());
    }

    private void requestAudioFocus() {
        if (audioManager == null || focusChangeListener != null) return;

        focusChangeListener = focusChange -> {
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
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
            );
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else if (focusChangeListener != null) {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
        focusChangeListener = null;
    }

    private void broadcastStatus(String message) {
        Intent status = new Intent(ACTION_STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_ACTIVE, active);
        status.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(status);
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

        Bitmap logo = drawableToBitmap(R.drawable.ic_launcher);

        builder.setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle("Plataforma Livre")
                .setContentText(text)
                .setSubText("Centro Cultural UERJ")
                .setContentIntent(contentIntent)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(stopAction)
                .setStyle(style);

        if (logo != null) builder.setLargeIcon(logo);

        return builder.build();
    }

    private Bitmap drawableToBitmap(int resId) {
        try {
            Drawable drawable = getDrawable(resId);
            if (drawable == null) return null;
            int width = Math.max(1, drawable.getIntrinsicWidth());
            int height = Math.max(1, drawable.getIntrinsicHeight());
            if (width <= 1) width = 512;
            if (height <= 1) height = 512;
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
    }

    @Override public void onDestroy() {
        active = false;
        playing = false;
        reconnecting = false;
        cancelReconnect();
        releasePlayer();
        abandonAudioFocus();
        unregisterNetworkCallback();

        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }

        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
