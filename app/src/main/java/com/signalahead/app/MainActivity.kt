package com.signalahead.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.signalahead.app.data.WeakZone
import com.signalahead.app.tracking.LocationForegroundService
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{SignalTheme{App()}}}
 @Composable private fun App(){
  var live by remember{mutableStateOf(false)};var tab by remember{mutableIntStateOf(0)}
  val permissions=buildList{add(Manifest.permission.ACCESS_FINE_LOCATION);if(Build.VERSION.SDK_INT>=33)add(Manifest.permission.POST_NOTIFICATIONS)}.toTypedArray()
  val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(it[Manifest.permission.ACCESS_FINE_LOCATION]==true){startForegroundService(Intent(this,LocationForegroundService::class.java));live=true}}
Scaffold(bottomBar={NavigationBar{listOf("Journey" to Icons.Default.Navigation,"Zones" to Icons.Default.Place,"Privacy" to Icons.Default.Security).forEachIndexed{i,p->NavigationBarItem(tab==i,{tab=i},{Icon(p.second,null)},label={Text(p.first)})}}}){pad->Box(Modifier.padding(pad).fillMaxSize()){when(tab){0->JourneyScreen(live,{launcher.launch(permissions)},{startService(Intent(this@MainActivity,LocationForegroundService::class.java).setAction("STOP"));live=false});1->ZonesScreen();else->PrivacyScreen()}}}
 }
 @Composable private fun JourneyScreen(live:Boolean,start:()->Unit,stop:()->Unit){Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){Text("Signal Ahead",style=MaterialTheme.typography.headlineLarge);Card{Column(Modifier.padding(22.dp).fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally){Icon(if(live)Icons.Default.Sensors else Icons.Default.LocationOff,null,Modifier.size(58.dp),tint=if(live)Color(0xFF16A34A) else Color.Gray);Text(if(live)"Live Journey" else "Ready",style=MaterialTheme.typography.headlineMedium);Text(if(live)"Learning from this journey" else "Tracking starts only when you choose")}};Button(if(live)stop else start,Modifier.fillMaxWidth().height(54.dp)){Text(if(live)"Stop journey" else "Start Live Journey")};Text("Signal Ahead learns recurring weak-signal areas from journeys you choose to track and can warn you when one is likely ahead.")}}
 @Composable private fun ZonesScreen(){val zones=(application as SignalAheadApp).database.dao().observeZones().collectAsStateWithLifecycle(emptyList()).value;Column(Modifier.padding(20.dp)){Text("Learned zones",style=MaterialTheme.typography.headlineMedium);Spacer(Modifier.height(12.dp));if(zones.isEmpty())Text("No zones yet. A possible zone needs weak readings on two different journeys; confirmation needs three.")else LazyColumn(verticalArrangement=Arrangement.spacedBy(10.dp)){items(zones){ZoneCard(it)}}}}
 @Composable private fun ZoneCard(z:WeakZone){Card{Column(Modifier.padding(16.dp).fillMaxWidth()){Text(z.status,style=MaterialTheme.typography.titleMedium);Text("${z.distinctJourneys} journeys • ${z.observations} observations");LinearProgressIndicator({z.confidence.toFloat()},Modifier.fillMaxWidth().padding(top=8.dp));Text("Confidence ${(z.confidence*100).toInt()}%")}}}
 @Composable private fun PrivacyScreen(){
  val scope=rememberCoroutineScope();var done by remember{mutableStateOf(false)}
  val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->if(uri!=null)scope.launch{val csv=buildExportCsv();withContext(Dispatchers.IO){contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(csv)}}}}
  Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("Privacy",style=MaterialTheme.typography.headlineMedium);Text("Journey observations and learned zones stay in the app's local database. Raw history older than 30 days is removed when a new journey starts. No cloud account or community upload is included.");OutlinedButton({exporter.launch("signal-ahead-export.csv")},Modifier.fillMaxWidth()){Icon(Icons.Default.Download,null);Spacer(Modifier.width(8.dp));Text("Export local data")};OutlinedButton({scope.launch{val d=(application as SignalAheadApp).database.dao();d.deleteObservations();d.deleteZones();d.deleteJourneys();done=true}},Modifier.fillMaxWidth()){Icon(Icons.Default.Delete,null);Spacer(Modifier.width(8.dp));Text("Delete all local data")};if(done)Text("Local history deleted.",color=Color(0xFF16A34A));Text("Limitations",style=MaterialTheme.typography.titleLarge);Text("Signal and location readings vary by device, permissions and Android restrictions. Warnings are best-effort and may be late or unavailable. Never rely on this app for emergency connectivity.")}
 }
 private suspend fun buildExportCsv():String=withContext(Dispatchers.IO){val d=(application as SignalAheadApp).database.dao();buildString{appendLine("record_type,timestamp,latitude,longitude,accuracy_m,signal_dbm,signal_level,status,confidence")
  d.exportObservations().forEach{appendLine("observation,${it.timestamp},${it.latitude},${it.longitude},${it.accuracyMetres},${it.signalDbm?:""},${it.signalLevel?:""},${it.kind},${it.observationConfidence}")}
  d.exportZones().forEach{appendLine("zone,${it.lastObserved},${it.centreLat},${it.centreLng},${it.radiusMetres},,,${it.status},${it.confidence}")}}}
}

@Composable fun SignalTheme(content:@Composable()->Unit){MaterialTheme(colorScheme=if(androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFF60A5FA)) else lightColorScheme(primary=Color(0xFF2563EB)),content=content)}
