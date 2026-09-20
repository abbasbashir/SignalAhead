package com.signalahead.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.signalahead.app.data.*
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos

@Composable fun RouteMap(spots:List<RouteSpot>){
    val recent=spots.take(60)
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Your signal map",style=MaterialTheme.typography.titleMedium)
            Text("Offline overview • north up • red weak / teal strong",style=MaterialTheme.typography.bodySmall)
            if(recent.isEmpty())Text("Track repeated journeys to draw your first route stretches.")
            else{
                val minLat=recent.minOf{minOf(it.startLat,it.endLat)}
                val maxLat=recent.maxOf{maxOf(it.startLat,it.endLat)}
                val minLng=recent.minOf{minOf(it.startLng,it.endLng)}
                val maxLng=recent.maxOf{maxOf(it.startLng,it.endLng)}
                Canvas(Modifier.fillMaxWidth().height(230.dp)){
                    val factor=cos(Math.toRadians((minLat+maxLat)/2)).coerceAtLeast(.05)
                    val dx=((maxLng-minLng)*factor).coerceAtLeast(.003)
                    val dy=(maxLat-minLat).coerceAtLeast(.003)
                    val scale=minOf((size.width-40)/dx,(size.height-40)/dy)
                    fun point(lat:Double,lng:Double)=Offset((size.width/2+(lng-(minLng+maxLng)/2)*factor*scale).toFloat(),(size.height/2-(lat-(minLat+maxLat)/2)*scale).toFloat())
                    repeat(5){i->val x=size.width*i/4;drawLine(Color.Gray.copy(alpha=.18f),Offset(x,0f),Offset(x,size.height))}
                    recent.forEach{z->
                        val color=if(z.quality=="WEAK")Color(0xFFF06B6B) else Color(0xFF0ABF9B)
                        val a=point(z.startLat,z.startLng);val b=point(z.endLat,z.endLng)
                        drawLine(color.copy(alpha=if(z.journeys>=3).95f else .4f),a,b,8f,StrokeCap.Round)
                        drawCircle(color,6f,a)
                    }
                }
                Text("Latest 60 stretches. No street tiles or navigation. Nearby parallel roads may still be ambiguous.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
@Composable fun RouteSpotCard(z:RouteSpot,rename:(String)->Unit,mute:()->Unit){
    var edit by remember{mutableStateOf(false)}
    var name by remember(z.name){mutableStateOf(z.name)}
    val context=LocalContext.current
    var mapDialog by remember{mutableStateOf(false)}
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(z.name.ifBlank{"${z.quality.lowercase().replaceFirstChar{it.uppercase()}} route stretch"},style=MaterialTheme.typography.titleMedium)
            Text("${z.journeys} supporting visits • ${(z.confidence*100).toInt()}% evidence score")
            Text("Last observed: ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(z.lastObserved))}",style=MaterialTheme.typography.bodySmall)
            Text("Separated by network • direction ${z.direction.toInt()}°",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState())){
                TextButton(onClick={edit=true}){Text("Name")}
                TextButton(onClick=mute){Text(if(z.muted)"Unmute" else "Mute")}
                TextButton(onClick={mapDialog=true}){Text("Street map")}
            }
        }
    }
    if(edit)AlertDialog(onDismissRequest={edit=false},title={Text("Name this stretch")},text={
        OutlinedTextField(value=name,onValueChange={name=it.take(80)},label={Text("e.g. Central Station approach")})
    },confirmButton={TextButton(onClick={rename(name.trim());edit=false}){Text("Save")}},dismissButton={TextButton(onClick={edit=false}){Text("Cancel")}})
    if(mapDialog)AlertDialog(onDismissRequest={mapDialog=false},title={Text("Open your map app?")},text={Text("The selected coordinates will be shared with the map app you choose.")},
        confirmButton={TextButton(onClick={
            mapDialog=false
            val uri=Uri.parse("geo:${z.startLat},${z.startLng}?q=${z.startLat},${z.startLng}")
            runCatching{context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW,uri),"Open map"))}
        }){Text("Open")}},dismissButton={TextButton(onClick={mapDialog=false}){Text("Cancel")}})
}
@Composable fun JourneyCards(summaries:List<JourneySummary>,alerts:List<AlertEvent>,feedback:(String,String)->Unit){
    if(summaries.isEmpty())Text("Your next journey will produce a summary here. Older trips did not record summary statistics.")
    summaries.take(30).forEach{j->
        Card(Modifier.fillMaxWidth()){
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(j.started)),style=MaterialTheme.typography.titleMedium)
                Text(j.status.lowercase().replaceFirstChar{it.uppercase()})
                if(j.ended>0)Text("Recording time: ${((j.ended-j.started-j.pausedMillis).coerceAtLeast(0)/60000)} min • paused ${j.pausedMillis/60000} min")
                Text("${j.samples} samples • ${j.weakSamples} weak readings")
                Text("${j.alerts} notifications posted • ${j.gaps} long sampling gaps")
                Text("Gaps are missing observations, not proven outages. Notification posting does not prove you saw the alert.",style=MaterialTheme.typography.bodySmall)
                alerts.filter{it.journeyId==j.id}.take(10).forEach{a->
                    HorizontalDivider()
                    Text("${if(a.delivered)"Notification" else "In-app only"} • ~${a.distance.toInt()} m ahead")
                    Text("Estimated lead: ${a.leadSeconds.toInt()} seconds • ${a.outcome.replace('_',' ').lowercase()}",style=MaterialTheme.typography.bodySmall)
                    Text("Your feedback: ${a.feedback.ifBlank{"not rated"}}",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())){
                        listOf("Accurate","Too late","Signal was fine").forEach{f->
                            TextButton(onClick={feedback(a.id,f)}){Text(f)}
                        }
                    }
                }
            }
        }
    }
}
