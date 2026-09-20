package com.signalahead.app.tracking
import android.content.Context
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import java.security.MessageDigest
import java.util.UUID

object NetworkContext{
    fun salt(context:Context):String{
        val p=context.getSharedPreferences("network-context",Context.MODE_PRIVATE)
        return p.getString("salt",null)?:UUID.randomUUID().toString().also{p.edit().putString("salt",it).commit()}
    }
    fun restoreSalt(context:Context,salt:String){context.getSharedPreferences("network-context",Context.MODE_PRIVATE).edit().putString("salt",salt).commit()}
    fun manager(context:Context):TelephonyManager?{
        val id=SubscriptionManager.getDefaultDataSubscriptionId()
        if(!SubscriptionManager.isValidSubscriptionId(id))return null
        return context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(id)
    }
    fun key(context:Context):String?=runCatching{
        val id=SubscriptionManager.getDefaultDataSubscriptionId()
        val operator=manager(context)?.networkOperator?:return null
        if(operator.length !in 5..6)return null
        MessageDigest.getInstance("SHA-256").digest(("${salt(context)}:$id:$operator").toByteArray()).joinToString(""){"%02x".format(it)}
    }.getOrNull()
}
