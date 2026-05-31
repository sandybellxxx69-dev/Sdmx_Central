package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SdmxScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun SdmxScreen(modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val prefs = context.getSharedPreferences("sdmx_prefs", Context.MODE_PRIVATE)
        val coroutineScope = rememberCoroutineScope()
        val clipboardManager = LocalClipboardManager.current
        
        var userSdmx by remember { mutableStateOf(prefs.getString("UserSdmx", "") ?: "") }
        var cookieSdmx by remember { mutableStateOf(prefs.getString("CookieSdmx", "") ?: "") }
        var urlSdmx by remember { mutableStateOf(prefs.getString("UrlSdmx", "") ?: "") }
        var bbva by remember { mutableStateOf(prefs.getString("Bbva", "4152314395395547\nBbva \nLuis garcia\nConcepto: software") ?: "") }
        
        var nextUser by remember { mutableStateOf("USER1") }
        val users = remember { mutableStateListOf<JSONObject>() }
        var selectedUser by remember { mutableStateOf<JSONObject?>(null) }
        
        var isLoading by remember { mutableStateOf(false) }
        var loadingPhase by remember { mutableStateOf("") }
        var showLogin by remember { mutableStateOf(false) }
        var showUrlInput by remember { mutableStateOf(urlSdmx.isEmpty()) }
        
        // Form dialogs state
        var showAddUserDialog by remember { mutableStateOf(false) }
        var showProxVencerDialog by remember { mutableStateOf(false) }
        var showEditUserDialog by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
        var filterDays by remember { mutableStateOf(-1) } // -1 means no filter

        // Initialize
        LaunchedEffect(Unit) {
            prefs.edit().putString("Bbva", bbva).apply()
            if (urlSdmx.isEmpty()) {
                showUrlInput = true
            } else {
                coroutineScope.launch { cargarDatos(urlSdmx, cookieSdmx) { newUsers -> 
                    users.clear()
                    users.addAll(newUsers)
                    if(newUsers.isEmpty()) showLogin = true
                } }
            }
        }

        if (showLogin) {
            LoginDialog(
                defaultUser = userSdmx,
                onLogin = { u, p ->
                    userSdmx = u
                    prefs.edit().putString("UserSdmx", u).apply()
                    coroutineScope.launch {
                        isLoading = true
                        loadingPhase = "Sincronizando panel - Sesión expirada, renovando..."
                        val newCookie = performLogin(u, p)
                        if (newCookie != null) {
                            cookieSdmx = newCookie
                            prefs.edit().putString("CookieSdmx", newCookie).apply()
                            showLogin = false
                            cargarDatos(urlSdmx, cookieSdmx) { newUsers ->
                                users.clear()
                                users.addAll(newUsers)
                            }
                        } else {
                            Toast.makeText(context, "DATOS INVALIDOS! Vuelve a Loggearte 🔑🔐🔑", Toast.LENGTH_LONG).show()
                        }
                        isLoading = false
                    }
                },
                onCancel = { showLogin = false }
            )
        }
        
        if (showUrlInput) {
             InputDialog(title = "Pon aqui la url:", defaultValue = urlSdmx, onConfirm = { 
                 urlSdmx = it
                 prefs.edit().putString("UrlSdmx", it).apply()
                 showUrlInput = false
                 coroutineScope.launch { cargarDatos(urlSdmx, cookieSdmx) { newUsers -> 
                     users.clear()
                     users.addAll(newUsers)
                     if(newUsers.isEmpty()) showLogin = true
                 } }
             }, onCancel = { showUrlInput = false })
        }

        if (showProxVencerDialog) {
            InputDialog(title = "Proximos a Vencer! - escribe la cantidad de días a filtrar:", defaultValue = "<5", onConfirm = {
                filterDays = it.replace("<", "").toIntOrNull() ?: 5
                showProxVencerDialog = false
            }, onCancel = { showProxVencerDialog = false })
        }
        
        showEditUserDialog?.let { (field, info) ->
            if (field == "vencimiento") {
                 // Simulate date picker here (just simple input for now due to complexity of actual dialog)
                 InputDialog(title = "Ingresa nuevo vencimiento (DD-MM-AAAA):", defaultValue = info.optString("vencimiento_formatted", ""), onConfirm = { newVal ->
                    coroutineScope.launch {
                        val success = editUserValue(info.optString("user"), "vencimiento", newVal, urlSdmx, isPassword = false)
                        if (success) {
                            Toast.makeText(context, "✅ Operación Exitosa!", Toast.LENGTH_SHORT).show()
                            cargarDatos(urlSdmx, cookieSdmx) { u -> users.clear(); users.addAll(u) }
                        }
                    }
                    showEditUserDialog = null
                 }, onCancel = { showEditUserDialog = null })
            } else {
                InputDialog(title = "Ingresa el nuevo $field:", defaultValue = info.optString(field), onConfirm = { newVal ->
                    coroutineScope.launch {
                        val success = editUserValue(info.optString("user"), field, newVal, urlSdmx, isPassword = (field == "password"))
                        if (success) {
                            Toast.makeText(context, "✅ Operación Exitosa!", Toast.LENGTH_SHORT).show()
                            cargarDatos(urlSdmx, cookieSdmx) { u -> users.clear(); users.addAll(u) }
                        }
                    }
                    showEditUserDialog = null
                }, onCancel = { showEditUserDialog = null })
            }
        }

        if (showAddUserDialog) {
            AddUserDialog(
                urlSdmx = urlSdmx,
                nextUser = calculateNextUser(users),
                onSuccess = { 
                    showAddUserDialog = false
                    coroutineScope.launch { cargarDatos(urlSdmx, cookieSdmx) { newUsers -> users.clear(); users.addAll(newUsers) } }
                },
                onCancel = { showAddUserDialog = false },
                client = client,
                context = context
            )
        }

        Box(modifier = modifier.fillMaxSize().background(Color(0xFF0F0F0F))) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header (15%)
                Box(modifier = Modifier.fillMaxWidth().weight(0.15f).background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
                    Text("SDMX Panel", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                
                // Info Panel
                Box(modifier = Modifier.fillMaxWidth().background(Color.Black).padding(8.dp)) {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            InfoBox("User:", selectedUser?.optString("usuario") ?: "", Modifier.weight(1f)) { action ->
                                if (selectedUser != null) {
                                    if (action == "tap") showEditUserDialog = "usuario" to selectedUser!!
                                    else clipboardManager.setText(AnnotatedString(selectedUser!!.optString("usuario")))
                                }
                            }
                            InfoBox("Pass:", selectedUser?.optString("password") ?: "", Modifier.weight(1f)) { action ->
                                if (selectedUser != null) {
                                    if (action == "tap") showEditUserDialog = "password" to selectedUser!!
                                    else clipboardManager.setText(AnnotatedString(selectedUser!!.optString("password")))
                                }
                            }
                            InfoBox("Vigencia:", selectedUser?.optString("vencimiento_formatted") ?: "", Modifier.weight(1f)) { action ->
                                if (selectedUser != null) {
                                     if (action == "tap") showEditUserDialog = "vencimiento" to selectedUser!!
                                     else clipboardManager.setText(AnnotatedString(selectedUser!!.optString("vencimiento_formatted")))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            InfoBox("Telefono:", selectedUser?.optString("numero") ?: "", Modifier.weight(1.3f)) { action ->
                                if (selectedUser != null && action == "long") {
                                    clipboardManager.setText(AnnotatedString(selectedUser!!.optString("numero")))
                                }
                            }
                            InfoBox("Dias:", selectedUser?.optString("dias") ?: "", Modifier.weight(0.7f)) { }
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                
                // Main split
                Row(modifier = Modifier.fillMaxWidth().weight(0.85f)) {
                    // Left list (33% or wider)
                    LazyColumn(modifier = Modifier.weight(0.6f).fillMaxHeight().background(Color.Black)) {
                        val filteredUsers = if (filterDays >= 0) {
                            users.filter { (it.optString("dias").toIntOrNull() ?: 100) <= filterDays }
                        } else users
                        
                        items(filteredUsers) { userJson ->
                            Text(
                                text = userJson.optString("user"),
                                color = Color.White,
                                fontSize = 24.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedUser = userJson }
                                    .padding(16.dp)
                            )
                            HorizontalDivider(color = Color.DarkGray)
                        }
                    }
                    
                    // Right buttons (40%)
                    Column(modifier = Modifier.weight(0.4f).fillMaxHeight().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { coroutineScope.launch { testFree(cookieSdmx, context) } }, modifier = Modifier.fillMaxWidth()) { Text("✴️TestFree✴️", color = Color.White) }
                        OutlinedButton(onClick = { showProxVencerDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("prox_vencer", color = Color.White) }
                        OutlinedButton(onClick = { filterDays = -1 }, modifier = Modifier.fillMaxWidth()) { Text("Todos", color = Color.White) }
                        OutlinedButton(onClick = { showAddUserDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Agregar 🆕", color = Color.White) }
                        OutlinedButton(onClick = { Toast.makeText(context, "File picker placeholder", Toast.LENGTH_SHORT).show() }, modifier = Modifier.fillMaxWidth()) { Text("android", color = Color.White) }
                        OutlinedButton(onClick = { 
                            selectedUser?.let { u ->
                                coroutineScope.launch {
                                    val id = u.optString("id")
                                    val userLabel = u.optString("user")
                                    deleteUser(id, userLabel, cookieSdmx, urlSdmx, context)
                                    cargarDatos(urlSdmx, cookieSdmx) { newUsers -> users.clear(); users.addAll(newUsers) }
                                    selectedUser = null
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Borrar", color = Color.White) }
                        OutlinedButton(onClick = { showLogin = true }, modifier = Modifier.fillMaxWidth()) { Text("Reinicio 🔃", color = Color.White) }
                        OutlinedButton(onClick = { 
                            selectedUser?.let { u ->
                                val num = u.optString("numero").removePrefix("521").removePrefix("52")
                                val msg = Uri.encode(bbva)
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/521$num?text=$msg"))
                                try { context.startActivity(intent) } catch (e: Exception) { Toast.makeText(context, "WhatsApp no instalado", Toast.LENGTH_SHORT).show() }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Recuerdame", color = Color.White) }
                        OutlinedButton(onClick = { 
                            coroutineScope.launch { cargarDatos(urlSdmx, cookieSdmx) { newUsers -> users.clear(); users.addAll(newUsers) } }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Actualizar", color = Color.White) }
                    }
                }
            }
            
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xBB0A0A0F)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF6366F1))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(loadingPhase, color = Color.White)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun InfoBox(title: String, value: String, modifier: Modifier = Modifier, onInterop: (String) -> Unit) {
        Column(modifier = modifier.padding(4.dp).background(Color(0xFFCC1313), RoundedCornerShape(4.dp)).combinedClickable(
            onClick = { onInterop("tap") },
            onLongClick = { onInterop("long") }
        ).padding(8.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    fun InputDialog(title: String, defaultValue: String, onConfirm: (String) -> Unit, onCancel: () -> Unit) {
        var text by remember { mutableStateOf(defaultValue) }
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(title) },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }) },
            confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
        )
    }
    
    @Composable
    fun LoginDialog(defaultUser: String, onLogin: (String, String) -> Unit, onCancel: () -> Unit) {
        var u by remember { mutableStateOf(defaultUser) }
        var p by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Login SDMX") },
            text = { 
                Column {
                    OutlinedTextField(value = u, onValueChange = { u = it }, label = { Text("Usuario") })
                    OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text("Password") })
                }
            },
            confirmButton = { TextButton(onClick = { onLogin(u, p) }) { Text("Login") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
        )
    }

    @Composable
    fun AddUserDialog(urlSdmx: String, nextUser: String, onSuccess: () -> Unit, onCancel: () -> Unit, client: OkHttpClient, context: Context) {
        var user by remember { mutableStateOf(nextUser) }
        var numero by remember { mutableStateOf("33") }
        var usuario by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var vencimiento by remember { mutableStateOf(SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))) }
        var id by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Agregar Nuevo Usuario") },
            text = {
                Column {
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("User (ej. USER5)") })
                    OutlinedTextField(value = numero, onValueChange = { numero = it }, label = { Text("Numero") })
                    OutlinedTextField(value = usuario, onValueChange = { usuario = it }, label = { Text("Usuario Panel") })
                    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password Panel") })
                    OutlinedTextField(value = vencimiento, onValueChange = { vencimiento = it }, label = { Text("Vencimiento (DD-MM-AAAA)") })
                    OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID Panel") })
                }
            },
            confirmButton = { 
                TextButton(onClick = {
                    scope.launch {
                        val success = addUser(urlSdmx, user, numero, usuario, password, vencimiento, id, client, context)
                        if (success) onSuccess()
                    }
                }) { Text("Agregar") } 
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancelar") } }
        )
    }

    private fun calculateNextUser(users: List<JSONObject>): String {
        var max = 0
        for (u in users) {
             val name = u.optString("user")
             if (name.startsWith("USER")) {
                 val num = name.removePrefix("USER").toIntOrNull() ?: 0
                 if (num > max) max = num
             }
        }
        return "USER${max + 1}"
    }

    private suspend fun performLogin(u: String, p: String): String? = withContext(Dispatchers.IO) {
        try {
            val body = FormBody.Builder()
                .add("referrer", "")
                .add("username", u)
                .add("password", p)
                .add("login", "")
                .build()
            val req = Request.Builder()
                .url("https://sdmx.vip/resellers/login")
                .header("User-Agent", "Mozilla/5.0")
                .post(body)
                .build()
            client.newCall(req).execute().use { res ->
                val cookies = res.headers("Set-Cookie")
                for (c in cookies) {
                    if (c.contains("PHPSESSID=")) {
                        val sessId = c.substringAfter("PHPSESSID=").substringBefore(";")
                        return@withContext "theme=0; PHPSESSID=$sessId;"
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        null
    }

    private suspend fun cargarDatos(urlSdmx: String, cookieSdmx: String, onResult: (List<JSONObject>) -> Unit) = withContext(Dispatchers.IO) {
        if(urlSdmx.isEmpty()) return@withContext
        try {
            val req = Request.Builder().url("$urlSdmx?hoja=USERS").build()
            client.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                if (json.has("datos")) {
                    val arr = json.getJSONArray("datos")
                    val list = mutableListOf<JSONObject>()
                    val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    val sdfOut = SimpleDateFormat("dd-MM-yyyy", Locale.US)
                    val now = Date()
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val vDateStr = item.optString("vencimiento")
                        try {
                            val vDate = sdfIn.parse(vDateStr)
                            if (vDate != null) {
                                item.put("vencimiento_formatted", sdfOut.format(vDate))
                                val diff = vDate.time - now.time
                                val dias = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS)
                                item.put("dias", dias.toString())
                            }
                        } catch (e: Exception) {
                            item.put("vencimiento_formatted", vDateStr)
                            item.put("dias", "?")
                        }
                        list.add(item)
                    }
                    withContext(Dispatchers.Main) { onResult(list) }
                } else {
                     withContext(Dispatchers.Main) { onResult(emptyList()) }
                }
            }
        } catch (e: Exception) { e.printStackTrace(); withContext(Dispatchers.Main) { onResult(emptyList()) } }
    }
    
    private suspend fun editUserValue(userLabel: String, field: String, value: String, urlSdmx: String, isPassword: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val endpoint = if (isPassword) "https://script.google.com/macros/s/AKfycbz55oNk_3MEfEkd-iRUCqrdPgIhXssxxddKHuh7kCIORB8PxDWXAab6D_6kIrM0Mpwn/exec" else urlSdmx
            val json = JSONObject().apply {
                put("user", userLabel)
                put(field, value)
            }
            val body = RequestBody.create("application/json".toMediaType(), json.toString())
            val req = Request.Builder().url(endpoint).post(body).build()
            client.newCall(req).execute().use { res ->
                return@withContext res.isSuccessful
            }
        } catch(e:Exception){e.printStackTrace(); return@withContext false}
    }

    private suspend fun deleteUser(id: String, userLabel: String, cookieSdmx: String, urlSdmx: String, context: Context) = withContext(Dispatchers.IO) {
        try {
            val req1 = Request.Builder()
                .url("https://sdmx.vip/resellers/api?action=line&sub=delete&user_id=$id")
                .header("Cookie", cookieSdmx)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            client.newCall(req1).execute().close()
            
            val json = JSONObject().apply {
                put("action", "delete")
                put("user", userLabel)
            }
            val body = RequestBody.create("application/json".toMediaType(), json.toString())
            val req2 = Request.Builder().url(urlSdmx).post(body).build()
            client.newCall(req2).execute().close()
            
            withContext(Dispatchers.Main) { Toast.makeText(context, "✅ Borrado", Toast.LENGTH_SHORT).show() }
        } catch(e:Exception){e.printStackTrace()}
    }

    private suspend fun testFree(cookieSdmx: String, context: Context) = withContext(Dispatchers.IO) {
         try {
             val body = FormBody.Builder()
                .add("action", "line")
                .add("trial", "1")
                .add("username", "p1papacas")
                .add("password", "p1papacas")
                .add("package", "150")
                .add("package_cost", "0")
                .add("package_duration", "24 hours")
                .add("max_connections", "2")
                .add("bouquets_selected[]", "19")
                .build()

            val req = Request.Builder()
                .url("https://sdmx.vip/resellers/post.php?action=line")
                .header("Cookie", cookieSdmx)
                .header("X-Requested-With", "XMLHttpRequest")
                .post(body)
                .build()
            client.newCall(req).execute().use { res ->
                val code = res.code
                withContext(Dispatchers.Main) { Toast.makeText(context, "TestFree = $code", Toast.LENGTH_SHORT).show() }
            }
         } catch(e:Exception){e.printStackTrace()}
    }

    private suspend fun addUser(urlSdmx: String, user: String, num: String, usu: String, pass: String, ven: String, id: String, client: OkHttpClient, context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("hoja", "USERS")
                val item = JSONObject().apply {
                    put("user", user)
                    put("numero", num)
                    put("usuario", usu)
                    put("password", pass)
                    put("vencimiento", ven)
                    put("id", id)
                }
                put("datos", JSONArray().put(item))
            }
            val body = RequestBody.create("application/json".toMediaType(), json.toString())
            val req = Request.Builder().url(urlSdmx).post(body).build()
            client.newCall(req).execute().use { res ->
                val code = res.code
                withContext(Dispatchers.Main) {
                   val msg = when(code) {
                       200, 201 -> "✅ ¡Operación Exitosa!"
                       404 -> "❌ Error 404: No se encontró la URL del Script."
                       500 -> "💥 Error 500: Fallo interno en el Google Script."
                       401, 403 -> "🔐 Error: Problema de permisos."
                       else -> "❓ Error inesperado: $code"
                   }
                   Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                return@withContext res.isSuccessful
            }
        } catch(e:IOException){
            withContext(Dispatchers.Main) { Toast.makeText(context, "⚠️ Error: Sin conexión a Internet.", Toast.LENGTH_LONG).show() }
            return@withContext false
        } catch(e:Exception){
            e.printStackTrace(); return@withContext false
        }
    }

}
