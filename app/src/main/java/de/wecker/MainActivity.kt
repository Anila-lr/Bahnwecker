package de.wecker
import android.Manifest
import android.content.Intent
import android.os.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        setContent { MaterialTheme { Screen(Store(this)) } }
    }
}

@Composable fun Num(label: String, v: Int, set: (Int) -> Unit) {
    var t by remember { mutableStateOf(v.toString()) }
    OutlinedTextField(t, { t = it; it.toIntOrNull()?.let(set) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable fun Screen(s: Store) {
    val ctx = LocalContext.current
    var conns by remember { mutableStateOf(s.conns) }
    var enabled by remember { mutableStateOf(s.enabled) }
    var active by remember { mutableStateOf(s.activeId) }
    var sound by remember { mutableStateOf(s.sound) }
    var dlg by remember { mutableStateOf(false) }
    fun apply() = if (s.enabled) Scheduler.schedule(ctx) else Scheduler.cancel(ctx)
    fun save(l: List<Conn>) { s.conns = l; conns = l; apply() }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); s.sound = u.toString(); sound = s.sound }
    }
    LazyColumn(Modifier.systemBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bahnwecker aktiv", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Switch(enabled, { enabled = it; s.enabled = it; apply() }) } }
        item { Num("Weckvorlauf vor Abfahrt (Min)", s.lead) { s.lead = it; apply() } }
        item { Num("Verspätungsgrenze (Min)", s.thr) { s.thr = it } }
        item { Num("Bei Alternativzug: zusätzlich x Min früher", s.extra) { s.extra = it } }
        item { Num("Bahnstatus prüfen X Min vor Weckzeit", s.checkBefore) { s.checkBefore = it; apply() } }
        item { var t by remember { mutableStateOf(s.defTime) }
            OutlinedTextField(t, { t = it; if (Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(it)) { s.defTime = it; apply() } },
                label = { Text("Standard-Weckzeit (HH:mm), falls kein Netz") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nur Mo–Fr", Modifier.weight(1f)); var w by remember { mutableStateOf(s.weekdays) }
            Switch(w, { w = it; s.weekdays = it; apply() }) } }
        item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (sound.isEmpty()) "Klingelton: Standard" else "Klingelton: eigene Datei", Modifier.weight(1f))
            Button({ pick.launch(arrayOf("audio/*")) }) { Text("MP3 wählen") }
            if (sound.isNotEmpty()) TextButton({ s.sound = ""; sound = "" }) { Text("Standard") } } }
        item { HorizontalDivider(); Text("Verbindungen (Auswahl = favorisiert)", style = MaterialTheme.typography.titleMedium) }
        items(conns, key = { it.id }) { c ->
            var menu by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(active == c.id, { s.activeId = c.id; active = c.id; apply() })
                Column(Modifier.weight(1f)) {
                    Text("${c.fromName} → ${c.toName}"); Text("Abfahrt ${c.time}")
                    Text("Alternative: " + (conns.firstOrNull { it.id == c.altId }?.let { "${it.time} ${it.fromName}→${it.toName}" } ?: "keine"), style = MaterialTheme.typography.bodySmall) }
                Box { TextButton({ menu = true }) { Text("Alt.") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("keine") }, { menu = false; save(conns.map { if (it.id == c.id) it.copy(altId = "") else it }) })
                        conns.filter { it.id != c.id }.forEach { o ->
                            DropdownMenuItem({ Text("${o.time} ${o.fromName}→${o.toName}") }, { menu = false; save(conns.map { if (it.id == c.id) it.copy(altId = o.id) else it }) }) } } }
                TextButton({ if (active == c.id) { s.activeId = ""; active = "" }; save(conns.filter { it.id != c.id }) }) { Text("✕") } } }
        }
        item { Button({ dlg = true }, Modifier.fillMaxWidth()) { Text("Verbindung hinzufügen") } }
    }
    if (dlg) {
        var from by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }; var time by remember { mutableStateOf("06:15") }
        AlertDialog(onDismissRequest = { dlg = false }, title = { Text("Neue Verbindung") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(from, { from = it }, label = { Text("Von (Bahnhof)") }, singleLine = true)
                OutlinedTextField(to, { to = it }, label = { Text("Nach (Bahnhof)") }, singleLine = true)
                OutlinedTextField(time, { time = it }, label = { Text("Abfahrt HH:mm") }, singleLine = true) } },
            confirmButton = { TextButton({
                if (!Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(time)) { Toast.makeText(ctx, "Zeit als HH:mm", Toast.LENGTH_SHORT).show(); return@TextButton }
                thread { try {
                    val f = Api.stations(from).first(); val t = Api.stations(to).first()
                    Handler(Looper.getMainLooper()).post {
                        val n = Conn(System.nanoTime().toString(), f.first, f.second, t.first, t.second, time)
                        save(conns + n); if (s.activeId.isEmpty()) { s.activeId = n.id; active = n.id; apply() }; dlg = false }
                } catch (e: Exception) { Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, "Bahnhof nicht gefunden / kein Netz", Toast.LENGTH_LONG).show() } } }
            }) { Text("Speichern") } },
            dismissButton = { TextButton({ dlg = false }) { Text("Abbrechen") } })
    }
}
