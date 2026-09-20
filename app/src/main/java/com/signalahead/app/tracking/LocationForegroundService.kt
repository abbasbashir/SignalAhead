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
    private var previousFix:Fix?=null
    private var summary:JourneySummary?=null
    private var pausedAt=0L
    private var lastFixAt=0L
    private val learner by lazy { RouteLearner(dao) }
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
                summary?.let{dao.summary(it.copy(ended=System.currentTimeMillis(),status="FINISHED",pausedMillis=it.pausedMillis+if(pausedAt>0)System.currentTimeMillis()-pausedAt else 0))}
                journeyId=null
                if(intent.action=="DELETE"){
                    app.database.withTransactionCompat {
                        BackupManager.clear(dao)
                    }
                }
                Live.state.value=LiveState()
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }}
            "PAUSE" -> {
                active=false;fused.removeLocationUpdates(callback)
                pausedAt=System.currentTimeMillis();learner.reset();previousFix=null
                scope.launch{lock.withLock{summary=summary?.copy(status="PAUSED");summary?.let{dao.summary(it)}}}
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
                        dao.markInterrupted()
                        dao.insertJourney(Journey(journeyId!!,started))
                        summary=JourneySummary(journeyId!!,started);summary?.let{dao.summary(it)}
                        Live.state.value=LiveState(phase="Live",startedAt=started)
                    }
                    if(pausedAt>0){summary=summary?.let{it.copy(pausedMillis=it.pausedMillis+System.currentTimeMillis()-pausedAt,status="ACTIVE")};pausedAt=0;lastFixAt=0}
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
            result.lastLocation?.let{location->scope.launch{lock.withLock{if(active) try { sample(location) } catch(_:Exception){Live.state.value=Live.state.value.copy(error="A sample could not be processed. Check permissions or restart the journey.");learner.reset()}}}}
        }
    }
    private suspend fun sample(location:Location){
        val now=System.currentTimeMillis()
        val p=app.settings.state.value
        val age=(SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos)/1000000
        if(age !in 0..30000)return
        val fix=Fix(location.latitude,location.longitude,now-age,location.accuracy.toDouble(),
            location.speed.toDouble().takeIf{location.hasSpeed()},
            location.bearing.toDouble().takeIf{location.hasBearing()&&(!location.hasBearingAccuracy()||location.bearingAccuracyDegrees<=30)})
        if(now-lastCleanup>3_600_000){
            dao.deleteOldRaw(now-p.retentionDays*86_400_000L);dao.deleteOldVotes(now-90L*86_400_000)
            dao.pruneRouteVotes(now-90L*86_400_000);dao.pruneAlerts(now-90L*86_400_000)
            lastCleanup=now
        }
        if(anchor==null || anchor!!.distanceTo(location)>maxOf(60f,location.accuracy)){anchor=Location(location);stillSince=now}
        val stationary=now-stillSince>180_000
        if(lastFixAt>0&&now-lastFixAt>maxOf(180000L,interval*2500)){
            summary=summary?.let{it.copy(gaps=it.gaps+1)};learner.reset()
        }
        lastFixAt=now
        val network=NetworkContext.key(this)
        val spots=if(network!=null)dao.networkSpots(network) else emptyList()
        val history=dao.allAlerts()
        val scored=spots.map{z->
            val votes=dao.routeVotes(z.id,now-90L*86400000)
            val score=PredictionCore.score(votes.map{Evidence(it.journeyId,it.quality,it.at)},now)
            z.copy(quality=score.quality,confidence=score.confidence,journeys=score.journeys)
        }
        val approaching=scored.filter{it.quality=="WEAK"&&!it.muted&&it.journeys>=if(p.confirmedOnly)3 else 2}
            .mapNotNull{z->
                val feedback=history.filter{it.spotId==z.id}.maxByOrNull{it.at}
                val confidence=z.confidence*(if(feedback?.feedback=="Signal was fine"&&feedback.at>=z.lastObserved).5 else 1.0)
                PredictionCore.approach(fix,previousFix,Fix(z.startLat,z.startLng,0,0.0,null,null),Fix(z.endLat,z.endLng,0,0.0,null,null),
                    confidence,z.lastObserved,now,if(feedback?.feedback=="Too late")180.0 else 120.0)?.let{z to it}
            }.minByOrNull{it.second.metres}
        val next=approaching?.let{(z,a)->"${z.name.ifBlank{"Likely weak stretch"}} • ~${"%.1f".format(a.metres/1000)} km ahead • ${if(z.confidence>=.85)"high" else "medium"} confidence"}
        if(approaching!=null){
            val(z,a)=approaching
            if(z.id !in warned && now-z.lastWarned>6*3_600_000L){
                val nm=NotificationManagerCompat.from(this)
                val permitted=p.warnings&&(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&nm.areNotificationsEnabled()&&
                    getSystemService(NotificationManager::class.java).getNotificationChannel("quiet-warnings").importance!=NotificationManager.IMPORTANCE_NONE
                if(permitted){
                    nm.notify(100+z.id.hashCode().and(0x7fffff),NotificationCompat.Builder(this,"quiet-warnings")
                        .setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Likely weak signal ahead").setContentText(next)
                        .setContentIntent(openApp()).setSilent(true).setAutoCancel(true).build())
                    dao.warnSpot(z.id,now)
                }
                dao.alert(AlertEvent(UUID.randomUUID().toString(),journeyId?:return,z.id,now,a.metres,a.seconds,permitted))
                warned.add(z.id)
                if(permitted)summary=summary?.let{it.copy(alerts=it.alerts+1)}
            }
        }
        val known=scored.firstOrNull{it.quality=="STRONG"&&it.confidence>=.8&&it.journeys>=3&&
            PredictionCore.distance(fix,Fix(it.startLat,it.startLng,0,0.0,null,null))<150&&fix.accuracy<=75&&
            !SamplingPolicy.recheck(it.lastObserved,now,p.refreshHours)}
        val cached=known!=null&&approaching==null
        var reading:SignalReading?=null
        if(!cached){
            reading=withContext(Dispatchers.IO){SignalReader(this@LocationForegroundService).read()}
            val confidence=if(fix.accuracy<=100&&reading.kind in listOf(ObservationKind.VALID_SIGNAL,ObservationKind.LEVEL_ONLY)).8 else .3
            val o=Observation(journeyId=journeyId?:return,timestamp=now,latitude=fix.lat,longitude=fix.lng,accuracyMetres=location.accuracy,
                speedMps=location.speed.takeIf{location.hasSpeed()},bearingDegrees=location.bearing.takeIf{location.hasBearing()},
                signalDbm=reading.dbm,signalLevel=reading.level,networkType=reading.networkType,dataValidated=reading.validated,
                kind=reading.kind.name,observationConfidence=confidence,locationSource=location.provider?:"fused",signalSource=reading.source,signalTimestamp=reading.timestamp)
            dao.insertObservation(o)
            summary=summary?.let{it.copy(samples=it.samples+1,weakSamples=it.weakSamples+if((reading.level?:4)<=1)1 else 0)}
            val category=SamplingPolicy.category(reading.level)
            if(category!=null&&confidence>=.6&&network!=null&&network==reading.networkKey&&reading.timestamp!=null){
                learner.record(RadioPoint(fix,category,network,reading.timestamp),journeyId?:return)
                if(category=="WEAK"){
                    // Positive evidence only. Missing readings never count as a false warning.
                    dao.journeyAlerts(journeyId?:return).filter{it.outcome=="UNVERIFIED"&&now-it.at in 0..600000}.forEach{alert->
                        val z=spots.firstOrNull{it.id==alert.spotId}
                        if(z!=null&&minOf(PredictionCore.distance(fix,Fix(z.startLat,z.startLng,0,0.0,null,null)),
                            PredictionCore.distance(fix,Fix(z.endLat,z.endLng,0,0.0,null,null)))<200)
                            dao.outcome(alert.id,"WEAK_OBSERVED_${((now-alert.at)/1000)}s_AFTER_WARNING")
                    }
                }
            }else learner.reset()
        }else learner.reset()
        summary?.let{dao.summary(it)}
        previousFix=fix
        val battery=getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val low=(battery in 0..20)||getSystemService(PowerManager::class.java).isPowerSaveMode
        val base=SamplingPolicy.seconds(p.mode,low,stationary,cached,approaching!=null)
        val seconds=if(!low&&!stationary&&!cached&&(fix.speed?:0.0)>10)minOf(base,15) else base
        val policy=when{network==null->"Network unknown • prediction paused";low->"Battery protection • ~90s checks";stationary->"Stationary • ~2 min checks";cached->"Saved strong spot • radio check deferred";else->"${p.mode} • ~${seconds}s checks"}
        Live.state.value=Live.state.value.copy(phase="Live",level=reading?.level,sampledAt=if(cached)known?.lastObserved else now,
            accuracy=location.accuracy,samples=summary?.samples?:0,policy=policy,next=next,error=null)
        getSystemService(NotificationManager::class.java).notify(10,notification(policy));configure(seconds)
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
