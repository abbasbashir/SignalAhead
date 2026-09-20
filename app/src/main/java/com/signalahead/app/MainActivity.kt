package com.signalahead.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.room.withTransaction
import com.signalahead.app.data.*
import com.signalahead.app.tracking.*
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity:ComponentActivity(){
    private val app get()=application as SignalAheadApp
    private var freshOpen=true
    private fun allowed(p:String)=ContextCompat.checkSelfPermission(this,p)==PackageManager.PERMISSION_GRANTED
    private fun locationAllowed()=allowed(Manifest.permission.ACCESS_COARSE_LOCATION)||allowed(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun command(action:String){
        try{
            val intent=Intent(this,LocationForegroundService::class.java).setAction(action)
            if(action=="START")ContextCompat.startForegroundService(this,intent) else startService(intent)
        }catch(_:RuntimeException){
            Live.state.value=Live.state.value.copy(error="Android could not start tracking. Check location permission and try again.")
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        freshOpen=savedInstanceState==null
        setContent{
            val prefs by app.settings.state.collectAsStateWithLifecycle()
            val dark=when(prefs.theme){"Dark"->true;"Light"->false;else->isSystemInDarkTheme()}
            MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFF68DEC2),background=Color(0xFF0C1522),surface=Color(0xFF142232)) else lightColorScheme(primary=Color(0xFF087F70),background=Color(0xFFF2F6F8),surface=Color.White)){
                App(prefs)
            }
        }
    }
    @Composable private fun App(p:Preferences){
        val live by Live.state.collectAsStateWithLifecycle()
        val zones by app.database.dao().observeZones().collectAsStateWithLifecycle(emptyList())
        val routes by app.database.dao().routeSpots().collectAsStateWithLifecycle(emptyList())
        val journeys by app.database.dao().summaries().collectAsStateWithLifecycle(emptyList())
        val alerts by app.database.dao().alerts().collectAsStateWithLifecycle(emptyList())
        val count by app.database.dao().observationCount().collectAsStateWithLifecycle(0)
        var tab by remember{mutableIntStateOf(0)}
        var permissionInfo by remember{mutableStateOf(false)}
        var deleteDialog by remember{mutableStateOf(false)}
        var exportDialog by remember{mutableStateOf(false)}
        var rawExport by remember{mutableStateOf(false)}
        var pendingBackup by remember{mutableStateOf<Backup?>(null)}
        var message by remember{mutableStateOf<String?>(null)}
        var now by remember{mutableLongStateOf(System.currentTimeMillis())}
        var preview by remember{mutableStateOf<SignalReading?>(null)}
        val scope=rememberCoroutineScope()
        val owner=LocalLifecycleOwner.current
        val locationLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
            if(locationAllowed())command("START") else message="Location was not granted. The dashboard and saved spots remain available."
        }
        val notifications=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){
            message=if(it)"Notifications enabled." else "Notifications are off. Warnings remain visible inside the app."
        }
        val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
            if(uri!=null)scope.launch{
                message=runCatching{
                    withContext(Dispatchers.IO){
                        val dao=app.database.dao()
                        val csv=if(rawExport)buildString{
                            appendLine("timestamp,latitude,longitude,accuracy_m,signal_level,quality")
                            dao.exportObservations().forEach{appendLine("${it.timestamp},${it.latitude},${it.longitude},${it.accuracyMetres},${it.signalLevel?:""},${it.kind}")}
                        }else buildString{
                            appendLine("start_latitude,start_longitude,end_latitude,end_longitude,signal,confidence,journeys,last_observed")
                            dao.allRouteSpots().forEach{appendLine("${it.startLat},${it.startLng},${it.endLat},${it.endLng},${it.quality},${it.confidence},${it.journeys},${it.lastObserved}")}
                        }
                        val stream=contentResolver.openOutputStream(uri)?:error("No output stream")
                        stream.bufferedWriter().use{it.write(csv)}
                    }; "Export saved."
                }.getOrElse{"Export failed. Please try a different destination."}
            }
        }
        val backupSave=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->
            if(uri!=null)scope.launch{message=runCatching{BackupManager.save(this@MainActivity,app.database,uri);"Backup saved. Keep it private: it contains location history."}.getOrElse{"Backup failed: ${it.message}"}}
        }
        val backupRead=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
            if(uri!=null)scope.launch{runCatching{BackupManager.read(this@MainActivity,uri)}.onSuccess{pendingBackup=it}.onFailure{message="Backup rejected: ${it.message}"}}
        }
        fun start(){if(locationAllowed())command("START") else permissionInfo=true}
        LaunchedEffect(Unit){
            if(freshOpen && p.autoStart && live.phase=="Ready" && locationAllowed())command("START")
            freshOpen=false
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
                while(isActive){
                    now=System.currentTimeMillis()
                    if(Live.state.value.phase!="Live")preview=withContext(Dispatchers.IO){SignalReader(this@MainActivity).read()}
                    delay(15_000)
                }
            }
        }
        Scaffold(
            containerColor=MaterialTheme.colorScheme.background,
            bottomBar={NavigationBar{
                listOf("Today" to Icons.Default.Dashboard,"Spots" to Icons.Default.Place,"Trips" to Icons.Default.History,"Settings" to Icons.Default.Tune).forEachIndexed{i,item->
                    NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(item.second,null)},label={Text(item.first)})
                }
            }}
        ){padding->
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text("SIGNAL AHEAD",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        Text(listOf("Your daily signal companion","Your learned places","Journey journal","Make it yours")[tab],style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    }
                    Icon(Icons.Default.Sensors,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(32.dp))
                }
                if(message!=null)Note(message!!){message=null}
                live.error?.let{Note(it){Live.state.value=live.copy(error=null)}}
                when(tab){
                    0->{
                        val level=if(live.phase=="Live")live.level else preview?.level
                        val label=level?.let{listOf("Poor","Weak","Fair","Good","Strong")[it]}?:"Waiting for a reading"
                        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF103B46),Color(0xFF13243F))),RoundedCornerShape(28.dp)).padding(24.dp)){
                            Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text(if(live.phase=="Live")"JOURNEY ACTIVE" else "LIVE DASHBOARD",color=Color(0xFF87E4CB),style=MaterialTheme.typography.labelLarge)
                                Text(label,color=Color.White,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                                Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.Bottom,modifier=Modifier.height(56.dp)){
                                    repeat(5){i->Box(Modifier.width(22.dp).height((16+i*10).dp).background(if(level!=null&&i<=level)Color(0xFF79E0C3) else Color.White.copy(alpha=.15f),RoundedCornerShape(5.dp)))}
                                }
                                Text(if(live.phase=="Live")live.policy else "Opens automatically • no journey recording until you start",color=Color(0xFFD0DFE6))
                                Text(if(live.phase=="Live")"Last observation: ${ago(live.sampledAt,now)}" else "Dashboard refreshes while this screen is open",color=Color(0xFF9CB6C4),style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            Metric("Weak spots",routes.count{it.quality=="WEAK"&&it.journeys>=3&&it.confidence>=.66}.toString(),Modifier.weight(1f))
                            Metric("Strong spots",routes.count{it.quality=="STRONG"&&it.journeys>=3&&it.confidence>=.66}.toString(),Modifier.weight(1f))
                            Metric("Saved samples",count.toString(),Modifier.weight(1f))
                        }
                        Panel("Journey controls"){
                            Text(when(live.phase){"Live"->"Recording locally. You remain in control.";"Paused"->"Paused. No location or signal samples are recorded.";else->"Start before travelling to learn the places you pass."})
                            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                                Button(onClick={if(live.phase=="Live")command("PAUSE") else start()},modifier=Modifier.weight(1f)){
                                    Icon(if(live.phase=="Live")Icons.Default.Pause else Icons.Default.PlayArrow,null)
                                    Text(if(live.phase=="Live")"Pause" else if(live.phase=="Paused")"Resume" else "Start journey")
                                }
                                if(live.phase!="Ready")OutlinedButton(onClick={command("STOP")}){Text("Stop")}
                            }
                            if(live.phase!="Ready"){
                                Text("This journey: ${live.samples} samples • started ${ago(live.startedAt,now)}",style=MaterialTheme.typography.bodySmall)
                                Text(live.accuracy?.let{if(it>100)"Location accuracy limited (~${it.toInt()} m). Predictions withheld." else "Location accuracy ~${it.toInt()} m"}?:"Waiting for location",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        Panel("Ahead of you"){
                            Icon(Icons.Default.Explore,null,tint=MaterialTheme.colorScheme.primary)
                            Text(live.next?:if(live.phase=="Live")"No confident warning ahead. This does not guarantee coverage." else "Start a journey to check for learned weak spots ahead.")
                            if(!p.warnings)Text("Notification alerts are off.",style=MaterialTheme.typography.bodySmall)
                        }
                        Panel("Less checking. More confidence."){
                            Text("Strong spots reuse saved results for ${p.refreshHours} hours. Weak stretches are checked during visits to verify warnings. Quiet notifications are limited to once per stretch per journey and at least six hours apart.")
                            Text("Strong signal describes radio reception, not guaranteed internet access.",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    1->{
                        Text("Your observations only • not a carrier coverage map",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        RouteMap(routes)
                        routes.forEach{z->RouteSpotCard(z,{name->scope.launch{app.database.dao().nameSpot(z.id,name)}},{scope.launch{app.database.dao().muteSpot(z.id,!z.muted)}})}
                        if(routes.isEmpty())Panel("Every journey teaches the app"){
                            Text("Two fresh readings along a route stretch support a visit. Repeated journeys at least 30 minutes apart build confidence. Three supporting visits and consistent evidence are needed for confirmed warnings.")
                        }
                        if(zones.isNotEmpty())Text("Legacy spots • network unknown • excluded from predictions")
                        zones.take(30).forEach{z->
                            Panel(if(z.quality=="STRONG")"Strong-signal spot" else "Weak-signal spot"){
                                Text("${z.status.lowercase().replaceFirstChar{it.uppercase()}} • ${z.distinctJourneys} journeys",fontWeight=FontWeight.SemiBold)
                                LinearProgressIndicator(progress={z.confidence.toFloat()},modifier=Modifier.fillMaxWidth())
                                Text("Last checked ${ago(z.lastObserved,now)} • ${(z.confidence*100).toInt()}% confidence",style=MaterialTheme.typography.bodySmall)
                                Text("Approx. ${"%.3f".format(z.centreLat)}, ${"%.3f".format(z.centreLng)}",style=MaterialTheme.typography.bodySmall)
                                if(now-z.lastObserved>30L*86_400_000)Text("Old observation • alerts suspended until refreshed")
                                if(z.quality=="WEAK")TextButton(onClick={scope.launch{app.database.dao().setMuted(z.cellKey,!z.warningSuppressed)}}){Text(if(z.warningSuppressed)"Enable this spot's alerts" else "Mute this spot")}
                            }
                        }
                    }
                    2->{JourneyCards(journeys,alerts){id,value->scope.launch{app.database.dao().feedback(id,value)}}}
                    3->{
                        Panel("Start your way"){
                            Toggle("Start journey on app open","Starts recording when you open the app after granting location. Stop and Pause remain available.",p.autoStart){app.settings.save(p.copy(autoStart=it))}
                            Text("Off by default. No boot startup or always-on passive tracking.",style=MaterialTheme.typography.bodySmall)
                        }
                        Panel("Battery & learning"){
                            Text("Sampling mode",fontWeight=FontWeight.SemiBold)
                            Choices(listOf("Eco","Balanced","Responsive"),p.mode){app.settings.save(p.copy(mode=it))}
                            Text("Eco checks less often. Responsive checks more often and can use more battery. All modes slow down when stationary or below 20% battery.",style=MaterialTheme.typography.bodySmall)
                            Text("Recheck confirmed strong spots",fontWeight=FontWeight.SemiBold)
                            Choices(listOf("6 hours","24 hours","72 hours"),"${p.refreshHours} hours"){app.settings.save(p.copy(refreshHours=it.substringBefore(" ").toInt()))}
                            Text("Checks happen on later visits, not on a background timer. Location checks continue during journeys to detect approach.",style=MaterialTheme.typography.bodySmall)
                        }
                        Panel("Quiet, useful alerts"){
                            Toggle("Weak-area notifications","Silent by default; only relevant learned spots ahead.",p.warnings){
                                app.settings.save(p.copy(warnings=it))
                                if(it&&Build.VERSION.SDK_INT>=33&&!allowed(Manifest.permission.POST_NOTIFICATIONS))notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            Toggle("Confirmed spots only","Turn off to include possible spots after two journeys.",p.confirmedOnly){app.settings.save(p.copy(confirmedOnly=it))}
                            TextButton(onClick={
                                if(Build.VERSION.SDK_INT>=33&&!allowed(Manifest.permission.POST_NOTIFICATIONS))notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                            }){Text("Notification permission & sound")}
                        }
                        Panel("Appearance"){
                            Choices(listOf("System","Light","Dark"),p.theme){app.settings.save(p.copy(theme=it))}
                        }
                        Panel("Your data, your choice"){
                            Text("Raw history retention")
                            Choices(listOf("7 days","30 days","90 days"),"${p.retentionDays} days"){
                                val days=it.substringBefore(" ").toInt();app.settings.save(p.copy(retentionDays=days))
                                scope.launch{app.database.dao().deleteOldRaw(System.currentTimeMillis()-days*86_400_000L)}
                            }
                            Text("Cleanup runs on app launch and during journeys. Visit summaries are kept up to 90 days; learned spots stay until deleted. Data is private to this app; no cloud upload, ads or analytics. Database content is not separately encrypted.",style=MaterialTheme.typography.bodySmall)
                            Text("JSON backup restores history, route evidence and names. It contains unencrypted locations. Restoring replaces current history; settings remain unchanged.",style=MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick={backupSave.launch("signal-ahead-backup.json")},modifier=Modifier.fillMaxWidth()){Text("Save full backup")}
                            OutlinedButton(onClick={
                                if(live.phase!="Ready")message="Stop the journey before restoring."
                                else backupRead.launch(arrayOf("application/json","text/plain","application/octet-stream"))
                            },modifier=Modifier.fillMaxWidth()){Text("Restore full backup")}
                            OutlinedButton(onClick={exportDialog=true},modifier=Modifier.fillMaxWidth()){Text("Export selected data")}
                            TextButton(onClick={deleteDialog=true}){Text("Delete all history",color=MaterialTheme.colorScheme.error)}
                        }
                        Panel("Permissions & trust"){
                            Text("Location: ${if(allowed(Manifest.permission.ACCESS_FINE_LOCATION))"Precise" else if(locationAllowed())"Approximate" else "Not granted"}")
                            Text("No contacts, microphone, calls or background-location permission.")
                            TextButton(onClick={startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}){Text("Open Android app settings")}
                            Text("Warnings are estimates and may arrive late or be missed. Battery savings depend on the device and journey; no percentage saving is promised.",style=MaterialTheme.typography.bodySmall)
                            Text("Signal Ahead 0.3 • Abbas Bashir",style=MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        if(pendingBackup!=null)AlertDialog(onDismissRequest={pendingBackup=null},title={Text("Replace current history?")},
            text={Text("Restore ${pendingBackup!!.observations.size} observations and ${pendingBackup!!.spots.size} route stretches. Current history will be replaced. New-device or changed-SIM observations may need relearning.")},
            confirmButton={TextButton(onClick={
                val b=pendingBackup!!;pendingBackup=null
                scope.launch{
                    if(Live.state.value.phase!="Ready")message="Stop the journey first."
                    else message=runCatching{BackupManager.restore(this@MainActivity,app.database,b);"History restored."}.getOrElse{"Restore failed; transaction rolled back: ${it.message}"}
                }
            }){Text("Replace & restore")}},
            dismissButton={TextButton(onClick={pendingBackup=null}){Text("Cancel")}})
        if(permissionInfo)AlertDialog(onDismissRequest={permissionInfo=false},title={Text("Learn only when you travel")},text={Text("A journey records location and available signal readings on this phone. A visible notification stays active. You can pause, stop, export or delete your data. Approximate location works with reduced precision.")},
            confirmButton={TextButton(onClick={permissionInfo=false;locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))}){Text("Continue")}},
            dismissButton={TextButton(onClick={permissionInfo=false}){Text("Not now")}})
        if(deleteDialog)AlertDialog(onDismissRequest={deleteDialog=false},title={Text("Delete all history?")},text={Text("Stops the journey and permanently removes all samples, learned spots and visit summaries. Your settings remain.")},
            confirmButton={TextButton(onClick={
                deleteDialog=false
                if(live.phase!="Ready")command("DELETE") else scope.launch{
                    app.database.withTransaction {BackupManager.clear(app.database.dao())}
                }
            }){Text("Delete")}},dismissButton={TextButton(onClick={deleteDialog=false}){Text("Cancel")}})
        if(exportDialog)AlertDialog(onDismissRequest={exportDialog=false},title={Text("Export location data")},text={Column{
            Text("The CSV contains sensitive locations. Choose what to include and where to save it.")
            Toggle("Raw observations","Off exports learned spots only.",rawExport){rawExport=it}
        }},confirmButton={TextButton(onClick={exportDialog=false;exporter.launch(if(rawExport)"signal-ahead-observations.csv" else "signal-ahead-spots.csv")}){Text("Choose destination")}},
            dismissButton={TextButton(onClick={exportDialog=false}){Text("Cancel")}})
    }
    override fun onStart(){
        super.onStart()
        lifecycleScope.launch{
            app.database.dao().deleteOldRaw(System.currentTimeMillis()-app.settings.state.value.retentionDays*86_400_000L)
            app.database.dao().deleteOldVotes(System.currentTimeMillis()-90L*86_400_000)
        }
    }
}
@Composable private fun Panel(title:String,body:@Composable ColumnScope.()->Unit){
    Card(shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);body()
        }
    }
}
@Composable private fun Metric(label:String,value:String,modifier:Modifier){
    Card(modifier=modifier,shape=RoundedCornerShape(18.dp)){
        Column(Modifier.padding(12.dp)){Text(value,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(label,style=MaterialTheme.typography.labelSmall)}
    }
}
@Composable private fun Toggle(title:String,description:String,value:Boolean,onChange:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.Medium);Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Switch(checked=value,onCheckedChange=onChange)
    }
}
@Composable private fun Choices(options:List<String>,selected:String,onChoose:(String)->Unit){
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        options.forEach{FilterChip(selected=it==selected,onClick={onChoose(it)},label={Text(it)})}
    }
}
@Composable private fun Note(text:String,dismiss:()->Unit){Card{Column(Modifier.padding(12.dp)){Text(text);TextButton(onClick=dismiss){Text("Dismiss")}}}}
private fun ago(at:Long?,now:Long):String{
    if(at==null)return "not available"
    val minutes=((now-at).coerceAtLeast(0)/60_000)
    return when{minutes<1->"just now";minutes<60->"$minutes min ago";minutes<1440->"${minutes/60} hours ago";else->"${minutes/1440} days ago"}
}
