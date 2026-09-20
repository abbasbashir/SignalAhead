package com.signalahead.app.tracking

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.signalahead.app.MainActivity
import com.signalahead.app.SignalAheadApp
import com.signalahead.app.data.*
import kotlinx.coroutines.*
import java.util.UUID

class LocationForegroundService:Service(){
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private lateinit var fused:FusedLocationProviderClient
    private lateinit var journeyId:String
    override fun onCreate(){super.onCreate();fused=LocationServices.getFusedLocationProviderClient(this);createChannel()}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="STOP"){stopJourney();return START_NOT_STICKY}
        journeyId=UUID.randomUUID().toString()
        scope.launch{val dao=db().dao();dao.deleteOldRaw(System.currentTimeMillis()-30L*24*60*60*1000);dao.insertJourney(Journey(journeyId,System.currentTimeMillis()))}
        startForeground(10,notification("Live Journey active • waiting for sample"));startUpdates();return START_NOT_STICKY
    }
    private fun startUpdates(){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){stopSelf();return}
        val req=LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,12_000).setMinUpdateDistanceMeters(40f).setMaxUpdateDelayMillis(25_000).build()
        fused.requestLocationUpdates(req,callback,mainLooper)
    }
    private val callback=object:LocationCallback(){override fun onLocationResult(r:LocationResult){r.lastLocation?.let(::sample)}}
    private fun sample(l:Location)=scope.launch{
        val s=SignalReader(this@LocationForegroundService).read();val confidence=when{l.accuracy<=30&&s.kind==ObservationKind.VALID_SIGNAL->.95;l.accuracy<=100->.7;else->.45}
        val o=Observation(journeyId=journeyId,timestamp=System.currentTimeMillis(),latitude=l.latitude,longitude=l.longitude,accuracyMetres=l.accuracy,speedMps=l.speed.takeIf{l.hasSpeed()},bearingDegrees=l.bearing.takeIf{l.hasBearing()},signalDbm=s.dbm,signalLevel=s.level,networkType=s.networkType,dataValidated=s.validated,kind=s.kind.name,observationConfidence=confidence,locationSource=l.provider?:"unknown",signalSource=s.source,signalTimestamp=s.timestamp)
        db().dao().insertObservation(o);ZoneEngine.update(db().dao(),o);checkAhead(l)
        val label=s.level?.let{arrayOf("Poor","Weak","Fair","Good","Great")[it]}?:"Unavailable";getSystemService(NotificationManager::class.java).notify(10,notification("Live Journey • Signal: $label"))
    }
    private suspend fun checkAhead(l:Location){if(!l.hasBearing())return;db().dao().activeZones().filter{it.status!="UNCONFIRMED"}.forEach{z->val(d,b)=ZoneEngine.distanceAndBearing(l,z);if(d in 300f..3000f&&ZoneEngine.angleDelta(l.bearing,b)<45&&(z.lastWarnedAt?:0)<System.currentTimeMillis()-900_000){getSystemService(NotificationManager::class.java).notify(z.cellKey.hashCode(),NotificationCompat.Builder(this,"warnings").setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle("Possible weak signal area ahead").setContentText("About ${if(d>1000)"%.1f km".format(d/1000) else "${d.toInt()} m"} • ${z.status.lowercase()}").setAutoCancel(true).build());db().dao().markWarned(z.cellKey,System.currentTimeMillis())}}}
    private fun notification(text:String):Notification{val stop=PendingIntent.getService(this,1,Intent(this,this::class.java).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE);val open=PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE);return NotificationCompat.Builder(this,"tracking").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("Signal Ahead").setContentText(text).setOngoing(true).setContentIntent(open).addAction(0,"Stop",stop).build()}
    private fun createChannel(){val n=getSystemService(NotificationManager::class.java);n.createNotificationChannel(NotificationChannel("tracking","Live journey",NotificationManager.IMPORTANCE_LOW));n.createNotificationChannel(NotificationChannel("warnings","Weak-area warnings",NotificationManager.IMPORTANCE_DEFAULT))}
    private fun stopJourney(){runCatching{fused.removeLocationUpdates(callback)};if(::journeyId.isInitialized)scope.launch{db().dao().endJourney(journeyId,System.currentTimeMillis())};stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
    private fun db()=(application as SignalAheadApp).database
    override fun onDestroy(){runCatching{fused.removeLocationUpdates(callback)};scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
