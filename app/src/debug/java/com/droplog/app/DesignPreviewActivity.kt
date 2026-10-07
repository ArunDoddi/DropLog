package com.droplog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

// Debug-only, local render harness. No sign-in, network requests or production writes.
class DesignPreviewActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen=intent.getStringExtra("screen") ?: "pin"
        setContent {DropLogTheme {
            val sample=JSONObject().put("id","preview").put("name","Evelyn Miller").put("first_name","Evelyn").put("last_name","Miller").put("job_title","Housekeeping Supervisor").put("role","employee").put("location","Hotel").put("locations",JSONArray(listOf("Hotel"))).put("duties",JSONArray(listOf("equipment"))).put("employment_type","full_time")
            Surface(Modifier.fillMaxSize(),color=TabletPalette.Background) {
                when(screen) {
                    "pin"->{var pin by remember {mutableStateOf("")};PinScreen("Cash",pin,false,{pin=it},{})}
                    else->Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(48.dp),verticalArrangement=Arrangement.spacedBy(32.dp)) {
                        Text("DropLog",style=MaterialTheme.typography.headlineMedium)
                        Text("BEST WESTERN SWISS CLOCK INN",style=MaterialTheme.typography.bodySmall,color=TabletPalette.Muted)
                        when(screen) {
                            "menu"->EmployeeActions(sample,false,{},{},{},{})
                            "cash"->CashScreen(sample,"Hotel",false){_,_,_,_,_->}
                            "equipment"->EquipmentScreen(sample,null,"Hotel",false){_,_,_,_,_->}
                            "create"->CreateUserScreen(false,onSave={})
                            "reports"->ReportSettingsScreen("reports@example.com",true,false,{},{},{},{},{})
                            "users"->UsersScreen(JSONArray(listOf(sample,JSONObject().put("id","admin").put("name","Admin Owner").put("role","manager").put("pin_login",true))),JSONObject().put("id","admin").put("name","Admin Owner").put("role","manager").put("pin_login",true),false,{},{},{})
                        }
                    }
                }
            }
        }}
    }
}
