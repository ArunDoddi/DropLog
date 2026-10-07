package com.droplog.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TabletPalette {
    val Navy=Color(0xFF102A43)
    val Blue=Color(0xFF215EEA)
    val Teal=Color(0xFF087F8C)
    val Muted=Color(0xFF64748B)
    val Background=Color(0xFFF3F6FB)
    val Soft=Color(0xFFE9F0FF)
}

@Composable
fun DropLogTheme(content:@Composable ()->Unit) {
    MaterialTheme(
        colorScheme=lightColorScheme(primary=TabletPalette.Blue,secondary=TabletPalette.Teal,tertiary=TabletPalette.Navy,background=TabletPalette.Background,surface=Color.White,onSurface=TabletPalette.Navy,onBackground=TabletPalette.Navy,onSurfaceVariant=TabletPalette.Muted,primaryContainer=TabletPalette.Soft,onPrimaryContainer=TabletPalette.Navy,secondaryContainer=TabletPalette.Blue,onSecondaryContainer=Color.White,surfaceVariant=TabletPalette.Soft),
        shapes=Shapes(small=RoundedCornerShape(16.dp),medium=RoundedCornerShape(24.dp),large=RoundedCornerShape(28.dp)),
        typography=Typography(
            headlineLarge=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=36.sp,lineHeight=44.sp),
            headlineMedium=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=28.sp,lineHeight=36.sp),
            titleLarge=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=24.sp,lineHeight=32.sp),
            bodyLarge=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontSize=20.sp,lineHeight=28.sp),
            bodyMedium=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontSize=18.sp,lineHeight=26.sp),
            bodySmall=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontSize=14.sp,lineHeight=20.sp),
            labelLarge=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Medium,fontSize=18.sp,lineHeight=24.sp)
        ),content=content
    )
}

@Composable
fun ScreenHeading(title: String,subtitle: String) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,style=MaterialTheme.typography.headlineLarge)
        if(subtitle.isNotBlank())Text(subtitle,style=MaterialTheme.typography.bodyLarge,color=TabletPalette.Muted)
    }
}

@Composable
fun FormCard(modifier: Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {
    Card(modifier=modifier,shape=RoundedCornerShape(28.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Column(Modifier.padding(32.dp),verticalArrangement=Arrangement.spacedBy(20.dp),content=content)
    }
}

@Composable
fun GuidanceCard(title: String,lines: List<String>,modifier: Modifier=Modifier,soft: Boolean=true,content:@Composable ColumnScope.()->Unit={}) {
    Card(modifier=modifier,shape=RoundedCornerShape(28.dp),colors=CardDefaults.cardColors(containerColor=if(soft)TabletPalette.Soft else Color.White)) {
        Column(Modifier.padding(28.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) {
            Text(title,style=MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            lines.forEach {Text(it,style=MaterialTheme.typography.bodyLarge,color=TabletPalette.Muted)}
            content()
        }
    }
}

@Composable
fun TabletForm(title: String,subtitle: String,helpTitle: String,helpLines: List<String>,softHelp: Boolean=true,helpContent:@Composable ColumnScope.()->Unit={},content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(36.dp)) {
        ScreenHeading(title,subtitle)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if(maxWidth>=850.dp) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(32.dp)) {
                    FormCard(Modifier.weight(0.66f),content)
                    GuidanceCard(helpTitle,helpLines,Modifier.weight(0.34f),soft=softHelp,content=helpContent)
                }
            } else {
                Column(verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    FormCard(Modifier.fillMaxWidth(),content)
                    GuidanceCard(helpTitle,helpLines,Modifier.fillMaxWidth(),soft=softHelp,content=helpContent)
                }
            }
        }
    }
}

@Composable
fun ReportSettingsScreen(email: String,enabled: Boolean,busy: Boolean,onEmail:(String)->Unit,onEnabled:(Boolean)->Unit,onSave:()->Unit,onTest:()->Unit,onPrevious:()->Unit) {
    TabletForm("Reports, delivered","Admin · Email configuration","Need yesterday’s\nreport now?",listOf("Send the previous day’s cash report to your saved email address."),softHelp=false,helpContent={
        Button(onClick=onPrevious,enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=TabletPalette.Teal),modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),shape=RoundedCornerShape(20.dp)){Text("Send previous day")}
        Text("Hotel + Restaurant / Bar totals",color=TabletPalette.Muted)
    }) {
        OutlinedTextField(email,onEmail,label={Text("Report email address")},singleLine=true,enabled=!busy,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Email),modifier=Modifier.fillMaxWidth())
        Button(onClick={onEnabled(!enabled)},enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=if(enabled)TabletPalette.Teal else TabletPalette.Navy),modifier=Modifier.heightIn(min=60.dp),shape=RoundedCornerShape(20.dp)){Text("Daily reports · ${if(enabled) "On" else "Off"}",fontSize=22.sp)}
        Text("Every day at 4:00 AM · Central time",style=MaterialTheme.typography.titleLarge)
        Text("Previous day’s cash drops. Equipment reports are available on request.",color=TabletPalette.Muted)
        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Button(onClick=onSave,enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=64.dp),shape=RoundedCornerShape(20.dp)){Text("Save settings")}
            Button(onClick=onTest,enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=TabletPalette.Navy),modifier=Modifier.weight(1f).heightIn(min=64.dp),shape=RoundedCornerShape(20.dp)){Text("Send test")}
        }
        Text("Save changes before sending a report.",style=MaterialTheme.typography.bodySmall,color=TabletPalette.Muted)
    }
}
