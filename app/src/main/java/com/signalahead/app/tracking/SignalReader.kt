package com.signalahead.app.tracking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.signalahead.app.data.ObservationKind

data class SignalReading(val dbm:Int?,val level:Int?,val networkType:String?,val validated:Boolean?,val source:String?,val timestamp:Long?,val kind:ObservationKind)

class SignalReader(private val context:Context) {
    fun read():SignalReading { return try {
        val tm=context.getSystemService(TelephonyManager::class.java)
        val cm=context.getSystemService(ConnectivityManager::class.java)
        val caps=cm.getNetworkCapabilities(cm.activeNetwork)
        val validated=if(caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)==true) caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) else null
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            return SignalReading(null,null,null,validated,null,null,ObservationKind.CONNECTIVITY_ONLY)
        val cell=tm.allCellInfo?.firstOrNull { it.isRegistered }
        if(cell==null) SignalReading(null,null,null,validated,null,null,ObservationKind.CONNECTIVITY_ONLY)
        else {
            val s=when(cell) {
                is android.telephony.CellInfoLte -> cell.cellSignalStrength
                is android.telephony.CellInfoGsm -> cell.cellSignalStrength
                is android.telephony.CellInfoWcdma -> cell.cellSignalStrength
                is android.telephony.CellInfoCdma -> cell.cellSignalStrength
                else -> if(android.os.Build.VERSION.SDK_INT>=29) cell.cellSignalStrength else null
            } ?: return SignalReading(null,null,null,validated,null,null,ObservationKind.UNAVAILABLE)
            val age=android.os.SystemClock.elapsedRealtimeNanos()-cell.timeStamp
            if(age<0 || age>90_000_000_000L) return SignalReading(null,null,null,validated,cell.javaClass.simpleName,cell.timeStamp,ObservationKind.STALE_OR_LOW_CONFIDENCE)
            val dbm=s.dbm.takeIf { it in -160..-20 }
            SignalReading(dbm,s.level.coerceIn(0,4),null,validated,cell.javaClass.simpleName,cell.timeStamp,if(dbm!=null) ObservationKind.VALID_SIGNAL else ObservationKind.LEVEL_ONLY)
        }
    } catch(_:SecurityException){ SignalReading(null,null,null,null,null,null,ObservationKind.UNAVAILABLE) }
      catch(_:Exception){ SignalReading(null,null,null,null,null,null,ObservationKind.STALE_OR_LOW_CONFIDENCE) }
    }
}
