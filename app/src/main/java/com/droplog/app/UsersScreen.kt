package com.droplog.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

fun cashOutlets(user: JSONObject): List<String> {
    if(user.optString("role")=="manager")return listOf("Restaurant","Bar")
    user.optJSONArray("cash_outlets")?.let {return stringList(it)}
    val duties=stringList(user.optJSONArray("duties"))
    if(("restaurant_cash" in duties) && !user.optBoolean("separate_outlets"))return listOf("Restaurant","Bar")
    return listOf("Restaurant","Bar").filter {if(it=="Bar") "bar_cash" in duties else "restaurant_cash" in duties}
}

fun canSelectHotelDuty(duties: List<String>,duty: String): Boolean = when(duty){"hotel_cash"->"equipment" !in duties;"equipment"->"hotel_cash" !in duties;else->false}

fun assignedDuties(locations: List<String>,duties: List<String>): List<String> = duties.distinct().filter {
    when(it){"hotel_cash","equipment"->"Hotel" in locations;"restaurant_cash","bar_cash"->"Restaurant" in locations;else->false}
}

fun accountDutyLabel(account: JSONObject): String {
    if(account.optString("role")=="manager")return "Admin · All locations"
    val duties=stringList(account.optJSONArray("duties"))
    return buildList {
        if("hotel_cash" in duties)add("Hotel front desk")
        if("equipment" in duties)add("Housekeeping · ${if(account.optString("employment_type")=="ortiz") "Ortiz" else "Full time"}")
        addAll(cashOutlets(account))
    }.joinToString(" · ").ifBlank {account.optString("job_title")}
}

@Composable
fun UsersScreen(accounts: JSONArray,admin: JSONObject,busy: Boolean,onAction:(JSONObject)->Unit,onAddAdmin:()->Unit,onAdminPin:()->Unit) {
    var search by remember {mutableStateOf("")}
    var filter by remember {mutableStateOf("All")}
    var editing by remember {mutableStateOf<JSONObject?>(null)}
    var creating by remember {mutableStateOf(false)}
    var resetting by remember {mutableStateOf<JSONObject?>(null)}
    val all=(0 until accounts.length()).map {accounts.getJSONObject(it)}
    val adminCount=all.count {it.optString("role")=="manager"}
    Panel("People & access", "Manage assignments, recover forgotten PINs and remove accounts") {
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            SuggestionChip(onClick={filter="Employees"},label={Text("${all.count {it.optString("role")=="employee"}} employees")})
            SuggestionChip(onClick={filter="Admins"},label={Text("$adminCount admins")})
            Button(onClick={creating=true;editing=null},enabled=!busy){Text("+ Add employee")}
            OutlinedButton(onClick=onAddAdmin,enabled=!busy){Text("+ Add admin")}
        }
        if(!admin.optBoolean("pin_login")) {
            Text("Set your own admin PIN before editing, resetting or deleting accounts.")
            OutlinedButton(onClick=onAdminPin,enabled=!busy){Text("Set my admin PIN")}
        }
    }
    if(creating||editing!=null) {
        key(editing?.optString("id"),creating) {
            CreateUserScreen(busy,editing,canDemote=editing?.optString("id")!=admin.optString("id")&&adminCount>1,onCancel={creating=false;editing=null},onSave=onAction)
        }
    } else {
        Panel("User directory", "Choose an action below each person") {
            OutlinedTextField(search,{search=it},label={Text("Search name, job title or assignment")},singleLine=true,modifier=Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("All","Employees","Admins").forEach {label->FilterChip(selected=filter==label,onClick={filter=label},label={Text(label)})}
            }
            val visible=all.filter {account->
                (filter=="All"||(account.optString("role")=="manager")== (filter=="Admins"))&&
                    listOf(account.optString("name"),account.optString("job_title"),accountDutyLabel(account)).any {it.contains(search.trim(),ignoreCase=true)}
            }
            Text("${visible.size} people",style=MaterialTheme.typography.labelLarge)
            if(visible.isEmpty())Text("No matching users.")
            visible.forEach {account->
                val isAdmin=account.optString("role")=="manager"
                val own=account.optString("id")==admin.optString("id")
                val lastAdmin=isAdmin&&adminCount<=1
                Card(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.4f))) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {
                                Text(account.optString("name").split(" ").filter {it.isNotBlank()}.take(2).joinToString(""){it.take(1)}.uppercase(),color=MaterialTheme.colorScheme.onPrimaryContainer,fontWeight=FontWeight.Bold)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(account.optString("name"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                                Text(accountDutyLabel(account),style=MaterialTheme.typography.bodyMedium)
                            }
                            Text(if(own) "You" else if(isAdmin) "Admin" else "Employee",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick={editing=account;creating=false},enabled=!busy&&admin.optBoolean("pin_login")){Text("Edit details")}
                            OutlinedButton(onClick={resetting=account},enabled=!busy&&admin.optBoolean("pin_login")){Text("Reset PIN")}
                            TextButton(onClick={onAction(JSONObject().put("method","DELETE").put("id",account.optString("id")).put("name",account.optString("name")))},enabled=!busy&&!own&&!lastAdmin&&admin.optBoolean("pin_login"),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)){Text("Delete")}
                        }
                        if(lastAdmin)Text("Last admin · deletion is disabled",style=MaterialTheme.typography.bodySmall)
                        else if(own)Text("You cannot delete your own account",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    resetting?.let {account->
        var pin by remember(account.optString("id")){mutableStateOf("")}
        var confirm by remember(account.optString("id")){mutableStateOf("")}
        AlertDialog(onDismissRequest={resetting=null},title={Text("Reset PIN · ${account.optString("name")}")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Assign a new unique PIN. Your admin PIN is required in the next step.")
            PinEntryFields(pin,confirm,busy,{pin=it},{confirm=it})
        }},confirmButton={TextButton(onClick={onAction(JSONObject().put("method","RESET_PIN").put("id",account.optString("id")).put("name",account.optString("name")).put("pin",pin).put("confirm_pin",confirm));resetting=null},enabled=!busy&&pin.length in 3..8&&pin==confirm){Text("Continue")}},dismissButton={TextButton(onClick={resetting=null}){Text("Cancel")}})
    }
}

@Composable
fun PinEntryFields(pin: String,confirm: String,busy: Boolean,onPin:(String)->Unit,onConfirm:(String)->Unit) {
    OutlinedTextField(pin,{onPin(it.filter {c->c in '0'..'9'}.take(8))},label={Text("New PIN (3–8 digits)")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),enabled=!busy,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(confirm,{onConfirm(it.filter {c->c in '0'..'9'}.take(8))},label={Text("Confirm new PIN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),isError=confirm.isNotEmpty()&&confirm!=pin,enabled=!busy,modifier=Modifier.fillMaxWidth())
}

@Composable
fun CreateUserScreen(busy: Boolean,initial: JSONObject?=null,canDemote: Boolean=true,onCancel:()->Unit={},onSave:(JSONObject)->Unit) {
    val oldName=initial?.optString("name")?.trim()?.split(Regex("\\s+")) ?: emptyList()
    var first by remember {mutableStateOf(initial?.optString("first_name")?.ifBlank {oldName.firstOrNull() ?: ""} ?: "")}
    var last by remember {mutableStateOf(initial?.optString("last_name")?.ifBlank {oldName.drop(1).joinToString(" ")} ?: "")}
    var title by remember {mutableStateOf(initial?.optString("job_title") ?: "")}
    var pin by remember {mutableStateOf("")};var confirm by remember {mutableStateOf("")}
    var role by remember {mutableStateOf(initial?.optString("role") ?: "employee")}
    var locations by remember {mutableStateOf(stringList(initial?.optJSONArray("locations")).ifEmpty {listOf(initial?.optString("location") ?: "Hotel")})}
    var duties by remember {mutableStateOf(stringList(initial?.optJSONArray("duties")).ifEmpty {listOf("hotel_cash")}.let {old->
        val outlets=initial?.let {cashOutlets(it)} ?: emptyList()
        (old.filterNot {it=="hotel_cash"&&"equipment" in old}+if("Bar" in outlets)listOf("bar_cash") else emptyList()).distinct()
    })}
    var employmentType by remember {mutableStateOf(initial?.optString("employment_type") ?: "full_time")}
    val manager=role=="manager"
    val eligible=assignedDuties(locations,duties)
    val pinValid=(initial!=null&&pin.isEmpty()&&confirm.isEmpty()) || (pin.length in 3..8&&pin==confirm)
    val valid=first.isNotBlank()&&last.isNotBlank()&&title.isNotBlank()&&pinValid&&(manager||eligible.isNotEmpty())
    TabletForm(if(initial==null) "Create an employee" else "Edit ${initial.optString("name")}", "Admin · Users and access", "One PIN.\nOne employee.",listOf("Use 3–8 digits.","Both PINs must match.","Every PIN must be unique.")) {
        AdaptiveFormRow {
            OutlinedTextField(first,{first=it.take(50)},label={Text("First name")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
            OutlinedTextField(last,{last=it.take(50)},label={Text("Last name")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f))
        }
        OutlinedTextField(title,{title=it.take(100)},label={Text("Job title")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=!manager,onClick={role="employee"},enabled=!busy&&(initial?.optString("role")!="manager"||canDemote),label={Text("Employee")})
            FilterChip(selected=manager,onClick={role="manager"},enabled=!busy,label={Text("Admin")})
        }
        if(manager)Text("Admins can manage users and all logs.")
        else {
            Text("Locations",fontWeight=FontWeight.Bold)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Hotel","Restaurant").forEach {place->
                FilterChip(selected=place in locations,onClick={locations=if(place in locations)locations-place else locations+place;duties=assignedDuties(locations,duties)},enabled=!busy,label={Text(place)})
            }}
            if("Hotel" in locations) {
                Text("Hotel duty · select one",fontWeight=FontWeight.Bold)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected="hotel_cash" in duties,onClick={duties=if("hotel_cash" in duties)duties-"hotel_cash" else duties+"hotel_cash"},enabled=!busy&&canSelectHotelDuty(duties,"hotel_cash"),label={Text("Front desk")})
                    FilterChip(selected="equipment" in duties,onClick={duties=if("equipment" in duties)duties-"equipment" else duties+"equipment"},enabled=!busy&&canSelectHotelDuty(duties,"equipment"),label={Text("Housekeeping")})
                }
                Text("Deselect the current hotel duty to switch departments.",style=MaterialTheme.typography.bodySmall)
            }
            if("Restaurant" in locations) {
                Text("Restaurant duties · select one or both",fontWeight=FontWeight.Bold)
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("restaurant_cash" to "Restaurant","bar_cash" to "Bar").forEach {(duty,label)->
                    FilterChip(selected=duty in duties,onClick={duties=if(duty in duties)duties-duty else duties+duty},enabled=!busy,label={Text(label)})
                }}
            }
            if("equipment" in duties)ChoiceDropdown("Employment type",listOf("Full time","Ortiz"),if(employmentType=="ortiz") "Ortiz" else "Full time",busy){employmentType=if(it=="Ortiz") "ortiz" else "full_time"}
        }
        if(initial!=null)Text("Leave PIN fields empty to keep the current PIN.",style=MaterialTheme.typography.bodySmall)
        PinEntryFields(pin,confirm,busy,{pin=it},{confirm=it})
        FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Button(onClick={onSave(JSONObject().put("first_name",first.trim()).put("last_name",last.trim()).put("name","${first.trim()} ${last.trim()}").put("job_title",title.trim()).put("role",role).put("employment_type",employmentType).put("locations",JSONArray(if(manager)listOf("Hotel","Restaurant") else locations)).put("duties",JSONArray(if(manager)listOf("hotel_cash","restaurant_cash","equipment") else eligible)).put("separate_outlets",true).put("pin",pin).put("confirm_pin",confirm).put("id",initial?.optString("id") ?: ""))},enabled=!busy&&valid){Text(if(initial==null) "Create employee" else "Review changes")}
            OutlinedButton(onClick=onCancel,enabled=!busy){Text("Cancel")}
        }
    }
}

@Composable
fun AdminPinSetup(busy: Boolean,user: JSONObject,onSave:(JSONObject)->Unit) {
    var password by remember {mutableStateOf("")}
    var pin by remember {mutableStateOf("")};var confirm by remember {mutableStateOf("")}
    Panel("Set my admin PIN", "${user.optString("name")} · Confirm your email sign-in password") {
        Text("Use this PIN to approve account edits, PIN resets and deletions. Sign in by admin email first.")
        OutlinedTextField(password,{password=it},label={Text("Admin email password")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
        PinEntryFields(pin,confirm,busy,{pin=it},{confirm=it})
        Button(onClick={onSave(JSONObject().put("password",password).put("pin",pin).put("confirm_pin",confirm));password=""},enabled=!busy&&password.isNotEmpty()&&pin.length in 3..8&&pin==confirm){Text("Save my admin PIN")}
    }
}
