package de.wecker
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.*
import kotlin.concurrent.thread

object Scheduler {
    private fun pi(c: Context, code: Int, cls: Class<*>, dep: Long = 0) = PendingIntent.getBroadcast(c, code,
        Intent(c, cls).putExtra("dep", dep), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    fun cancel(c: Context) { am(c).cancel(pi(c, 1, AlarmReceiver::class.java)); am(c).cancel(pi(c, 2, CheckReceiver::class.java)) }

    fun setRing(c: Context, at: Long, dep: Long) {
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(c).setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, 1, AlarmReceiver::class.java, dep))
    }

    /** Plant die nächste Abfahrt: Standardzeit-Wecker als Sicherheitsnetz + Prüfung vor der Weckzeit. */
    fun schedule(c: Context) {
        val s = Store(c); cancel(c)
        val a = s.active()?.takeIf { s.enabled } ?: return
        val z = ZoneId.systemDefault(); val now = System.currentTimeMillis()
        var d = LocalDate.now(z); var dep: Long; var wake: Long
        while (true) {
            dep = d.atTime(LocalTime.parse(a.time)).atZone(z).toInstant().toEpochMilli()
            wake = dep - s.lead * 60000L
            if (dep > s.doneDep && wake > now && (!s.weekdays || d.dayOfWeek.value < 6)) break
            d = d.plusDays(1)
        }
        val base = d.atTime(LocalTime.parse(s.defTime)).atZone(z).toInstant().toEpochMilli()
        setRing(c, if (base > now) base else wake, dep)   // Fallback, falls Prüfung scheitert (kein Netz)
        val t = maxOf(wake - s.checkBefore * 60000L, now + 3000)
        try { am(c).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, 2, CheckReceiver::class.java, dep)) }
        catch (e: SecurityException) { am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c, 2, CheckReceiver::class.java, dep)) }
    }
}

class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val pr = goAsync(); val dep = i.getLongExtra("dep", 0)
        thread { try { run(c, dep) } finally { pr.finish() } }
    }
    private fun run(c: Context, dep: Long) {
        val s = Store(c); val a = s.active() ?: return
        val z = ZoneId.systemDefault(); val date = Instant.ofEpochMilli(dep).atZone(z).toLocalDate()
        val bad = try { Api.isBad(a, date, s.thr) } catch (e: Exception) { null } ?: return  // kein Netz -> Standardzeit bleibt
        var target = dep - s.lead * 60000L
        if (bad) s.conns.firstOrNull { it.id == a.altId }?.let { alt ->
            val altDep = date.atTime(LocalTime.parse(alt.time)).atZone(z).toInstant().toEpochMilli()
            target = altDep - (s.lead + s.extra) * 60000L
        }
        Scheduler.setRing(c, maxOf(target, System.currentTimeMillis() + 10000), dep)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store(c).doneDep = i.getLongExtra("dep", 0)
        ContextCompat.startForegroundService(c, Intent(c, AlarmService::class.java))
        Scheduler.schedule(c)
    }
}

class BootReceiver : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = Scheduler.schedule(c) }

class AlarmService : Service() {
    private var mp: MediaPlayer? = null
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("a", "Wecker", NotificationManager.IMPORTANCE_HIGH))
        val stop = PendingIntent.getService(this, 0, Intent(this, AlarmService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "a").setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("Aufstehen!")
            .setContentText("Dein Zug wartet nicht.").setCategory(NotificationCompat.CATEGORY_ALARM).setOngoing(true)
            .setFullScreenIntent(open, true).addAction(0, "Stopp", stop).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        mp?.release()
        val custom = Store(this).sound.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        mp = try { play(custom ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) }
             catch (e: Exception) { play(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) }
        return START_NOT_STICKY
    }
    private fun play(u: Uri) = MediaPlayer().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        setDataSource(this@AlarmService, u); isLooping = true; prepare(); start()
    }
    override fun onDestroy() { mp?.release(); super.onDestroy() }
}
