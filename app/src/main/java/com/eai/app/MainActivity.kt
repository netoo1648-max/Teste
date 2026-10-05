package com.eai.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

private const val TTL_MS = 12L * 60 * 60 * 1000

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

fun neededPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )
    Build.VERSION.SDK_INT >= 31 -> arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
}

fun loadSaved(p: SharedPreferences): List<Msg> {
    val arr = JSONArray(p.getString("saved", "[]"))
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        Msg(o.getString("id"), o.getString("n"), o.getString("a"), o.getString("t"), o.getLong("ts"), true)
    }
}

fun persistSaved(p: SharedPreferences, list: List<Msg>) {
    val arr = JSONArray()
    list.filter { it.saved }.forEach { m ->
        arr.put(
            JSONObject().put("id", m.id).put("n", m.nick).put("a", m.avatar)
                .put("t", m.text).put("ts", m.time)
        )
    }
    p.edit().putString("saved", arr.toString()).apply()
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("eai", Context.MODE_PRIVATE) }
    var nick by remember { mutableStateOf(prefs.getString("nick", "") ?: "") }
    var avatar by remember { mutableStateOf(prefs.getString("avatar", "😀") ?: "😀") }

    if (nick.isBlank()) {
        ProfileScreen { n, a ->
            prefs.edit().putString("nick", n).putString("avatar", a).apply()
            nick = n
            avatar = a
        }
    } else {
        ChatScreen(nick, avatar, prefs)
    }
}

@Composable
fun ProfileScreen(onDone: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var av by remember { mutableStateOf("😀") }
    val avatars = listOf("😀", "😎", "🤠", "👻", "🐱", "🐶", "🦊", "🐼", "🚀", "🔥")

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Eai?", fontSize = 40.sp)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { if (it.length <= 20) name = it },
            label = { Text("Seu nickname") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text("Escolha seu avatar (selecionado: $av)")
        avatars.chunked(5).forEach { row ->
            Row {
                row.forEach { e ->
                    TextButton(onClick = { av = e }) { Text(e, fontSize = 28.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { if (name.isNotBlank()) onDone(name.trim(), av) }) {
            Text("Entrar")
        }
    }
}

@Composable
fun ChatScreen(nick: String, avatar: String, prefs: SharedPreferences) {
    val ctx = LocalContext.current
    val messages = remember { mutableStateListOf<Msg>().apply { addAll(loadSaved(prefs)) } }
    var peers by remember { mutableStateOf(0) }
    var granted by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r -> granted = r.values.all { it } }

    LaunchedEffect(Unit) { launcher.launch(neededPermissions()) }

    val mesh = remember {
        MeshManager(
            ctx, nick, avatar,
            onMessage = { m -> if (messages.none { it.id == m.id }) messages.add(m) },
            onPeers = { peers = it }
        )
    }

    DisposableEffect(granted) {
        if (granted) mesh.start()
        onDispose { mesh.stop() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val limit = System.currentTimeMillis() - TTL_MS
            messages.removeAll { it.time < limit && !it.saved }
            delay(30_000)
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp)) {
        Text("Eai? • $peers por perto", fontSize = 20.sp)
        if (!granted) Text("Aceite as permissões para encontrar pessoas por perto.")

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(messages.toList(), key = { it.id }) { m ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.avatar, fontSize = 28.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.nick, fontSize = 12.sp)
                        Text(m.text)
                    }
                    TextButton(onClick = {
                        val i = messages.indexOfFirst { it.id == m.id }
                        if (i >= 0) {
                            messages[i] = messages[i].copy(saved = !messages[i].saved)
                            persistSaved(prefs, messages)
                        }
                    }) { Text(if (m.saved) "Salvo ✓" else "Salvar") }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Bora conversar...") }
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (text.isNotBlank()) {
                    messages.add(mesh.send(text.trim()))
                    text = ""
                }
            }) { Text("Enviar") }
        }
    }
}