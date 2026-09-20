package com.signalahead.app.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.signalahead.app.MainActivity
import com.signalahead.app.SignalAheadApp
import com.signalahead.app.data.*
import kotlinx.coroutines.*
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class LocationForegroundService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val lock=Mutex()
    private lateinit var fused:FusedLocationProviderClient
    private var journeyId:String?=null
    private var active=false
    private var interval=0L
    private var started=0L
    private var anchor:Location?=null
    private var stillSince=0L
    private var episodeKey:String?=null
    private var episodeCount=0
    private var episodeTimestamp:Long?=null
    private var lastCleanup=0L
    private val warned=mutableSetOf<String>()
    private val app get()=application as SignalAheadApp
    private val dao get()=app.database.dao()

    override fun onCreate(){
        super.onCreate()
        fused=LocationServices.getFusedLocationProviderClient(this)
        val nm=getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("tracking","Journey status",NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("quiet-warnings","Quiet weak-area alerts",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        when(intent?.action){
            "STOP","DELETE" -> scope.launch { lock.withLock {
                active=false
                fused.removeLocationUpdates(callback)
                journeyId?.let{dao.endJourney(it,System.currentTimeMillis())}
                journeyId=null
                if(intent.action=="DELETE"){
                    app.database.withTransactionCompat {
                        dao.deleteObservations();dao.deleteVotes();dao.deleteZones();dao.deleteJourneys()
                    }
                }
                Live.state.value=LiveState()
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }}
            "PAUSE" -> {
                active=false;fused.removeLocationUpdates(callback)
                Live.state.value=Live.state.value.copy(phase="Paused",policy="Sampling stopped",next=null)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            "START" -> if(!active) {
                if(!hasLocation()){Live.state.value=LiveState(error="Allow location to start a journey.");stopSelf();return START_NOT_STICKY}
                try { startForeground(10,notification("Starting journey")) }
                catch(e:RuntimeException){Live.state.value=LiveState(error="Android could not start tracking. Open the app and try again.");stopSelf();return START_NOT_STICKY}
                active=true
                scope.launch{lock.withLock{
                    if(journeyId==null){
                        journeyId=UUID.randomUUID().toString();started=System.currentTimeMillis();warned.clear()
                        dao.insertJourney(Journey(journeyId!!,started))
                        Live.state.value=LiveState(phase="Live",startedAt=started)
                    }
                    Live.state.value=Live.state.value.copy(phase="Live",error=null)
                    interval=0;configure(30)
                }}
            }
            else -> if(journeyId==null)stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun hasLocation()=ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED

    private fun configure(seconds:Long){
        if(!active||interval==seconds)return
        interval=seconds
        fused.removeLocationUpdates(callback)
        if(!hasLocation()){active=false;Live.state.value=Live.state.value.copy(error="Location permission removed",phase="Paused");stopForeground(STOP_FOREGROUND_REMOVE);return}
        val request=LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,seconds*1000)
            .setMinUpdateIntervalMillis(seconds*1000).setMaxUpdateDelayMillis(seconds*1000)
            .setMinUpdateDistanceMeters(0f).build()
        try { fused.requestLocationUpdates(request,callback,mainLooper).addOnFailureListener{
            active=false;Live.state.value=Live.state.value.copy(phase="Paused",error="Location unavailable. Check device Location settings.");stopForeground(STOP_FOREGROUND_REMOVE)
        }} catch(_:SecurityException){active=false;Live.state.value=Live.state.value.copy(phase="Paused",error="Location access unavailable");stopForeground(STOP_FOREGROUND_REMOVE)}
    }
    private val callback=object:LocationCallback(){
        override fun onLocationResult(result:LocationResult){
            result.lastLocation?.let{location->scope.launch{lock.withLock{if(active) sample(location)}}}
        }
    }
    private suspend fun sample(location:Location){
        val now=System.currentTimeMillis()
        val p=app.settings.state.value
        if(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos>120_000_000_000L)return
        if(now-lastCleanup>3_600_000){
            dao.deleteOldRaw(now-p.retentionDays*86_400_000L)
            dao.deleteOldVotes(now-90L*86_400_000)
            lastCleanup=now
        }
        if(anchor==null || anchor!!.distanceTo(location)>maxOf(60f,location.accuracy)){
            anchor=Location(location);stillSince=now
        }
        val stationary=now-stillSince>180_000
        val key=ZoneEngine.cellKey(location.latitude,location.longitude)
        val known=dao.zone(key)
        val cached=known?.status=="CONFIRMED" && known.confidence>=.66 &&
            !SamplingPolicy.recheck(known.lastObserved,now,p.refreshHours)
        val zones=dao.activeZones()
        val approaching=zones.filter{it.quality=="WEAK" && it.confidence>=.66 &&
            it.status in (if(p.confirmedOnly) listOf("CONFIRMED") else listOf("POSSIBLE","CONFIRMED")) &&
            now-it.lastObserved<30L*86_400_000}
            .map{it to ZoneEngine.distanceAndBearing(location,it)}
            .filter{(_,db)->location.accuracy<=100 && location.hasBearing() && location.hasSpeed() &&
                location.speed>=1 && (!location.hasBearingAccuracy()||location.bearingAccuracyDegrees<=35) &&
                db.first in 250f..3000f && ZoneEngine.angleDelta(location.bearing,db.second)<35}
            .minByOrNull{it.second.first}
        val next=approaching?.let{(z,db)->"Likely weak area • ~${"%.1f".format(db.first/1000)} km ahead"}
        if(approaching!=null && p.warnings){
            val z=approaching.first
            val nm=NotificationManagerCompat.from(this)
            val allowed=(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&nm.areNotificationsEnabled() &&
                getSystemService(NotificationManager::class.java).getNotificationChannel("quiet-warnings").importance!=NotificationManager.IMPORTANCE_NONE
            if(allowed && z.cellKey !in warned && now-(z.lastWarnedAt?:0)>6*3_600_000L){
                nm.notify(100+z.cellKey.hashCode().and(0x7fffff),NotificationCompat.Builder(this,"quiet-warnings")
                    .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Likely weak signal ahead")
                    .setContentText(next).setContentIntent(openApp()).setSilent(true).setAutoCancel(true).build())
                warned.add(z.cellKey);dao.markWarned(z.cellKey,now)
            }
        }
        var reading:SignalReading?=null
        if(!cached){
            reading=withContext(Dispatchers.IO){SignalReader(this@LocationForegroundService).read()}
            val confidence=if(location.accuracy<=100 && reading.kind in listOf(ObservationKind.VALID_SIGNAL,ObservationKind.LEVEL_ONLY)) .8 else .3
            val o=Observation(journeyId=journeyId?:return,timestamp=now,latitude=location.latitude,longitude=location.longitude,
                accuracyMetres=location.accuracy,speedMps=location.speed.takeIf{location.hasSpeed()},bearingDegrees=location.bearing.takeIf{location.hasBearing()},
                signalDbm=reading.dbm,signalLevel=reading.level,networkType=reading.networkType,dataValidated=reading.validated,
                kind=reading.kind.name,observationConfidence=confidence,locationSource=location.provider?:"fused",
                signalSource=reading.source,signalTimestamp=reading.timestamp)
            dao.insertObservation(o)
            val category=SamplingPolicy.category(reading.level)
            val ep=if(category!=null && confidence>=.6) "$key:$category" else null
            // Require two fresh distinct radio samples in this cell before a journey votes.
            if(ep!=null && reading.timestamp!=episodeTimestamp){
                episodeCount=if(ep==episodeKey)episodeCount+1 else 1
                episodeKey=ep;episodeTimestamp=reading.timestamp
                if(episodeCount>=2)ZoneEngine.update(dao,o)
            }else if(ep==null){episodeCount=0;episodeKey=null}
        }else{episodeKey=null;episodeCount=0}
        val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val low=(battery in 0..20)||getSystemService(PowerManager::class.java).isPowerSaveMode
        val seconds=SamplingPolicy.seconds(p.mode,low,stationary,cached,approaching!=null)
        val policy=when{
            low->"Battery protection • ~90s checks"
            stationary->"Stationary • ~2 min checks"
            cached->"Saved ${known!!.quality.lowercase()} spot • radio check deferred"
            approaching!=null->"Approaching a weak area • closer checks"
            else->"${p.mode} • ~${seconds}s checks"
        }
        Live.state.value=Live.state.value.copy(phase="Live",level=reading?.level,
            sampledAt=if(cached)known?.lastObserved else now,accuracy=location.accuracy,
            samples=Live.state.value.samples+if(cached)0 else 1,policy=policy,next=next,error=null)
        getSystemService(NotificationManager::class.java).notify(10,notification(policy))
        configure(seconds)
    }
    private fun openApp()=PendingIntent.getActivity(this,20,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
    private fun notification(text:String):Notification{
        fun action(name:String,code:Int)=PendingIntent.getService(this,code,Intent(this,this::class.java).setAction(name),PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this,"tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Signal Ahead • journey active").setContentText(text).setContentIntent(openApp())
            .setOnlyAlertOnce(true).setOngoing(true).addAction(0,"Pause",action("PAUSE",1)).addAction(0,"Stop",action("STOP",2)).build()
    }
    override fun onDestroy(){active=false;fused.removeLocationUpdates(callback);scope.cancel();Live.state.value=LiveState();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
private suspend fun AppDatabase.withTransactionCompat(block:suspend()->Unit)=withTransaction { block() }
