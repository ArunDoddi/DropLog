package com.droplog.app

import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.ui.input.pointer.PointerEventPass
import android.app.DatePickerDialog
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity : ComponentActivity() {
    var lastInteraction by mutableLongStateOf(SystemClock.elapsedRealtime())
    override fun onUserInteraction() { super.onUserInteraction(); lastInteraction=SystemClock.elapsedRealtime() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        LocalDatabase.initialize(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary=Color(0xFF174CDB), secondary=Color(0xFF008878), background=Color(0xFFF5F7FB), surface=Color.White)) {
                DropLog("Firebase") { }
            }
        }
    }
}

fun localTime(value: String): String = try {
    Instant.parse(value).atZone(ZoneId.of("America/Chicago")).format(DateTimeFormatter.ofPattern("MMM d • h:mm a"))
} catch(_: Exception) { value }

@Composable
fun DropLog(initialServer: String, saveServer: (String)->Unit) {
    var server by remember { mutableStateOf(initialServer) }
    var username by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }; var user by remember { mutableStateOf(JSONObject()) }
    var dashboard by remember { mutableStateOf(JSONObject()) }; var page by remember { mutableStateOf("Welcome") }
    val syncStatus by OfflineStore.status.collectAsState()
    var queueReview by remember {mutableStateOf(JSONArray())}
    var selectedLocation by remember { mutableStateOf("Hotel") }
    var reportType by remember { mutableStateOf("cash") }
    var reportStart by remember { mutableStateOf(LocalDate.now(ZoneId.of("America/Chicago")).minusDays(1)) }
    var reportEnd by remember { mutableStateOf(LocalDate.now(ZoneId.of("America/Chicago")).minusDays(1)) }
    var destination by remember { mutableStateOf("Cash") }; var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }; var enabled by remember { mutableStateOf(false) }
    var employeeList by remember { mutableStateOf(JSONArray()) }
    var pinTaken by remember {mutableStateOf(false)}
    var activityRows by remember {mutableStateOf(JSONArray())}
    var mealServers by remember {mutableStateOf(JSONArray())}
    var serverName by remember {mutableStateOf("")}
    var serverMeal by remember {mutableStateOf("Lunch")}
    var pendingUserAction by remember {mutableStateOf<JSONObject?>(null)}
    var confirmingAdminPin by remember {mutableStateOf("")}
    val compact=LocalConfiguration.current.screenWidthDp < 600
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    fun reset() { token="";user=JSONObject();dashboard=JSONObject();password="";pin="";page="Welcome" }
    fun runTask(block: suspend () -> Unit) {
        if(busy) return
        busy=true; message=""
        scope.launch { try { block() } catch(e: Exception) { message=e.message ?: "Unable to connect";if(page=="Users" && e is CloudFailure && e.code==com.google.firebase.functions.FirebaseFunctionsException.Code.ALREADY_EXISTS)pinTaken=true } finally { busy=false } }
    }
    suspend fun refresh() { dashboard=Api(server,token).call("/dashboard") }
    fun openReportSettings() { runTask {
        val settings=Api(server,token).call("/settings")
        email=settings.getString("email");enabled=settings.getInt("enabled")==1
        page="Settings"
    } }
    fun selectShift(choice: WorkingShift) { selectedLocation=choice.location;page=if(choice.duty=="equipment") "Equipment" else "Cash" }
    fun routeToShift() {
        val choices=workingShifts(user,dashboard.optJSONObject("shift"),destination)
        if(choices.size==1)selectShift(choices.first()) else page="Shift"
    }
    suspend fun finishEntry(queued: Boolean = false) {
        // Clear the employee session even if the logout response cannot reach the tablet.
        try { Api(server,token).call("/logout",JSONObject()) } catch(_: Exception) { }
        reset(); message=if(queued) "Your signed log is saved on this tablet and will sync automatically. Thank you!" else "Your log is saved in the cloud. Thank you!"
    }
    BackHandler(enabled=page!="Welcome" && !busy) {
        if(token.isNotEmpty()) { runTask { finishEntry(); message="Ready for the next employee." } }
        else { page=if(page=="PIN") "Welcome" else "Welcome"; pin="";password="";message="" }
    }
    val activity=context as? MainActivity
    LaunchedEffect(token) {
        activity?.lastInteraction=SystemClock.elapsedRealtime()
        while(token.isNotEmpty() && user.optString("role")!="manager") {
            delay(1000)
            if(!busy && SystemClock.elapsedRealtime()-(activity?.lastInteraction ?: SystemClock.elapsedRealtime())>=60000) {
                Api(server,token).call("/logout",JSONObject());reset();message="Signed out after one minute of inactivity.";break
            }
        }
    }
    pendingUserAction?.let {action->
        val deleting=action.optString("method")=="DELETE"
        AlertDialog(onDismissRequest={if(!busy){pendingUserAction=null;confirmingAdminPin=""}},title={Text(if(deleting) "Delete ${action.optString("name")}?" else "Confirm account change")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(deleting) "This account will lose access. Saved logs and signatures are retained." else "Enter your own admin PIN to save changes for ${action.optString("name")}.")
            Text("Signed in as ${user.optString("name")}",fontWeight=FontWeight.Bold)
            OutlinedTextField(confirmingAdminPin,{confirmingAdminPin=it.filter {c->c in '0'..'9'}.take(8)},label={Text("Your admin PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),enabled=!busy)
            if(message.isNotBlank())Text(message,color=MaterialTheme.colorScheme.error)
            if(!user.optBoolean("pin_login"))Text("Set your admin PIN from Admin options first.")
        }},confirmButton={TextButton(enabled=!busy&&confirmingAdminPin.length in 3..8,onClick={val submitted=JSONObject(action.toString()).put("admin_pin",confirmingAdminPin);confirmingAdminPin="";runTask {
            Api(server,token).call("/users",submitted);employeeList=Api(server,token).call("/users").getJSONArray("users");pendingUserAction=null;refresh();user=dashboard.getJSONObject("user");message=if(deleting) "Account deleted. Saved logs retained." else "Account updated."
        }}){Text(if(deleting) "Delete account" else "Confirm & save")}},dismissButton={TextButton(enabled=!busy,onClick={pendingUserAction=null;confirmingAdminPin="";message=""}){Text("Cancel")}})
    }
    if(pinTaken)AlertDialog(onDismissRequest={pinTaken=false},title={Text("PIN already taken")},text={Text("This PIN belongs to another employee. Choose a different PIN.")},confirmButton={TextButton(onClick={pinTaken=false}){Text("OK")}})
    Surface(modifier=Modifier.fillMaxSize().pointerInput(Unit) {
        awaitPointerEventScope {while(true){awaitPointerEvent(PointerEventPass.Initial);activity?.lastInteraction=SystemClock.elapsedRealtime()}}
    },color=MaterialTheme.colorScheme.background) {
        if(page=="Welcome") {
            TabletHome(true,listOf(message,syncStatus).filter {it.isNotBlank()}.joinToString("\n"),onStart={destination="Employee";page="PIN";message=""},onChoose={destination=it;pin="";page="PIN";message=""},onAdmin={page="Admin";message=""})
        } else {
            Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.TopCenter) {
                Column(Modifier.widthIn(max=1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=if(compact) 12.dp else 32.dp,vertical=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column {
                            Text("DropLog",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                            Text("Version ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            if(token.isNotEmpty() && user.optString("role")=="manager") {
                                IconButton(onClick={page="AdminHome"},enabled=!busy) {
                                    Icon(painterResource(R.drawable.ic_settings),contentDescription="Admin options",tint=MaterialTheme.colorScheme.primary)
                                }
                            }
                            TextButton(enabled=!busy,onClick={if(token.isEmpty()) {page="Welcome";pin="";password=""} else runTask {finishEntry();message="Ready for the next employee."}}) { Text("Home / Sign out") }
                        }
                    }
                    if(token.isNotEmpty()) Text("${user.optString("name")} • ${user.optString("job_title")} • ${user.optString("location")}",color=Color(0xFF64748B))
                    if(page in listOf("Cash","Equipment") && workingShifts(user,dashboard.optJSONObject("shift"),destination).size>1) TextButton(onClick={routeToShift()},enabled=!busy) {Text("Change working shift")}
                    if(syncStatus.isNotBlank())Text(syncStatus,color=MaterialTheme.colorScheme.secondary)
                    if(page in listOf("Cash","Equipment","Shift","Meal","Housekeeping"))TextButton(onClick={runTask {refresh();page="Menu"}},enabled=!busy){Text("Back to my actions")}
                    when(page) {
                        "PIN" -> {
                            PinScreen(destination,pin,busy,onChange={pin=it},onContinue={ runTask {
                                val result=Api(server).call("/pin-login",JSONObject().put("pin",pin))
                                user=result.getJSONObject("user");token=result.getString("token");pin="";refresh();page=if(user.optString("role")=="manager") "AdminHome" else "Menu"
                            } })
                        }
                        "Admin" -> Panel("Admin sign-in", "Manage employees and cloud report emails") {
                            OutlinedTextField(username,{username=it},label={Text("Admin email")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(password,{password=it},label={Text("Admin password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                            Button(onClick={runTask {
                                val result=Api(server).call("/login",JSONObject().put("username",username).put("password",password))
                                val signedUser=result.getJSONObject("user")
                                val signedToken=result.getString("token")
                                if(signedUser.getString("role")!="manager") {Api(server,signedToken).call("/logout",JSONObject());error("Admin access required")}
                                user=signedUser;token=signedToken;password="";saveServer(server);refresh();page="AdminHome"
                            }},enabled=!busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank()) { Text("Sign in as admin") }
                        }
                        "Menu" -> EmployeeActions(user,busy,onCash={destination="Cash";routeToShift()},onEquipment={destination="Equipment";routeToShift()},onMeal={runTask {refresh();page="Meal"}},onHousekeeping={page="Housekeeping"})
                        "Housekeeping" -> Panel("Housekeeping", "Hotel · ${user.optString("name")}") {
                            Button(onClick={destination="Equipment";routeToShift()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){Text("Log equipment · Radio & keys")}
                            Button(onClick={runTask {refresh();page="Meal"}},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){Text("Log meal")}
                        }
                        "Meal" -> MealScreen(user,dashboard.optJSONArray("meals")?:JSONArray(),busy) {meal,signature,id->runTask {
                            val result=Api(server,token).call("/meal",JSONObject().put("meal",meal).put("signature",signature).put("request_id",id));finishEntry(result.optBoolean("queued"))
                        }}
                        "Shift" -> WorkingShiftScreen(user,dashboard.optJSONObject("shift"),destination,busy) {selectShift(it)}
                        "Activity" -> Panel("Dated logs", "Dates use Central time") {
                            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("cash","equipment","meal").forEach {type->FilterChip(reportType==type,onClick={reportType=type;activityRows=JSONArray();mealServers=JSONArray()},label={Text(type.replaceFirstChar {it.uppercase()})},enabled=!busy)}}
                            FlowRow {DateField("From",reportStart,!busy){reportStart=it;if(reportEnd<it)reportEnd=it};DateField("Through",reportEnd,!busy){reportEnd=it}}
                            Button(onClick={runTask {val result=Api(server,token).call("/activity",JSONObject().put("type",reportType).put("start_date",reportStart.toString()).put("end_date",reportEnd.toString()));activityRows=result.getJSONArray("rows");mealServers=result.getJSONArray("servers")}},enabled=!busy&&reportEnd>=reportStart){Text("View logs")}
                            if(reportType=="meal")Button(onClick={reportStart=reportStart.withDayOfMonth(1);reportEnd=reportStart.plusMonths(1).minusDays(1);runTask {val result=Api(server,token).call("/activity",JSONObject().put("type","meal").put("start_date",reportStart.toString()).put("end_date",reportEnd.toString()));activityRows=result.getJSONArray("rows");mealServers=result.getJSONArray("servers")}},enabled=!busy){Text("View monthly meal report")}
                            ActivityTable(reportType,activityRows,mealServers)
                        }
                        "LogServer" -> Panel("Log Server", "Server for employee meals on the selected date") {
                            DateField("Date",reportStart,!busy){reportStart=it}
                            ChoiceDropdown("Meal",listOf("Breakfast","Lunch","Dinner"),serverMeal,busy){serverMeal=it}
                            OutlinedTextField(serverName,{serverName=it.take(100)},label={Text("Server name")},modifier=Modifier.fillMaxWidth())
                            Button(onClick={runTask {Api(server,token).call("/meal-server",JSONObject().put("date",reportStart.toString()).put("meal",serverMeal).put("name",serverName.trim()));message="Server saved for $reportStart · $serverMeal."}},enabled=!busy&&serverName.isNotBlank()){Text("Save server")}
                        }
                        "AdminHome" -> Panel("Admin options", "Create users, review activity and manage reports") {
                            Button(onClick={runTask {employeeList=Api(server,token).call("/users").getJSONArray("users");page="Users"}},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Users • Create employee") }
                            Button(onClick={openReportSettings()},enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                                Icon(painterResource(R.drawable.ic_settings),contentDescription=null,modifier=Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Email & reports")
                            }
                            Button(onClick={activityRows=JSONArray();mealServers=JSONArray();page="Activity"},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Check drop, equipment & meal logs")}
                            Button(onClick={page="LogServer"},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Log Server")}
                            OutlinedButton(onClick={page="AdminPinSetup"},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Set my admin PIN")}
                            OutlinedButton(onClick={page="CreateAdmin"},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Add admin")}
                            OutlinedButton(onClick={runTask {queueReview=JSONArray(OfflineStore.pending().map { (_, record) -> record });page="SyncReview"}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Offline logs · Sync review")}
                            OutlinedButton(onClick={destination="All";routeToShift()},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Log my working shift") }
                            OutlinedButton(onClick={page="Monitoring"},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("App health & monitoring")}
                            OutlinedButton(onClick={page="History"},enabled=!busy,modifier=Modifier.fillMaxWidth()) { Text("Today’s activity") }
                        }
                        "Monitoring" -> Panel("App health & monitoring", "DropLog ${BuildConfig.VERSION_NAME} · ${if(BuildConfig.DEBUG) "Debug: monitoring disabled" else "Release: monitoring enabled"}") {
                            Text("Crash reports, login/save timing and offline sync metrics are available in Firebase. Cloud function timing and errors are available in Google Cloud.")
                            Button(onClick={context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://console.firebase.google.com/project/droplog-27857/overview")))},enabled=!busy) {Text("Open Firebase Console")}
                            OutlinedButton(onClick={runTask {Telemetry.diagnostic();message=if(BuildConfig.DEBUG) "Install a signed release APK to send monitoring data." else "Diagnostic sent. Check Crashlytics non-fatal reports and the diagnostic performance trace after upload."}},enabled=!busy && !BuildConfig.DEBUG) {Text("Send monitoring test · no crash")}
                            Text("Console access uses your Google account permissions. An app admin role does not grant Google Cloud console access.",style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={page="AdminHome"},enabled=!busy) {Text("Back to admin")}
                        }
                        "CreateAdmin" -> {
                            CreateAdminScreen(busy){data->runTask {Api(server,token).call("/admins",data);page="AdminHome";message="Admin created. They can sign in using their own email and password."}}
                            TextButton(onClick={page="AdminHome"},enabled=!busy){Text("Back to admin")}
                        }
                        "SyncReview" -> Panel("Offline log queue", "Signed records remain here until Firebase confirms them. Conflicts require review.") {
                            if(queueReview.length()==0)Text("No logs waiting to sync.")
                            for(i in 0 until queueReview.length()) {val record=queueReview.getJSONObject(i)
                                Text("${record.optString("name")} · ${record.optString("operation")} · ${localTime(record.optString("created"))}")
                                Text(record.optString("error","Waiting for internet / sync"),color=MaterialTheme.colorScheme.secondary)
                                Text("Record ID: ${record.getJSONObject("body").optString("request_id")}",style=MaterialTheme.typography.bodySmall);HorizontalDivider()
                            }
                            Button(onClick={runTask {OfflineStore.sync();queueReview=JSONArray(OfflineStore.pending().map {it.second})}},enabled=!busy){Text("Retry sync")}
                            TextButton(onClick={page="AdminHome"},enabled=!busy){Text("Back to admin")}
                        }
                        "AdminPinSetup" -> AdminPinSetup(busy,user) {data->runTask {
                            Api(server,token).call("/admin-pin",data);refresh();user=dashboard.getJSONObject("user");page="AdminHome";message="Your admin PIN is ready for account changes."
                        }}
                        "Users" -> {
                            key(employeeList.toString()) {UsersScreen(employeeList,user,busy,onAction={data->
                                if(data.optString("id").isNotEmpty()){pendingUserAction=data;confirmingAdminPin="";message=""}
                                else runTask {Api(server,token).call("/users",data);employeeList=Api(server,token).call("/users").getJSONArray("users");message="Employee created."}
                            },onAddAdmin={page="CreateAdmin"},onAdminPin={page="AdminPinSetup"})}
                            TextButton(onClick={page="AdminHome"},enabled=!busy){Text("Back to admin")}
                        }
                        "Cash" -> CashScreen(user,selectedLocation,busy) { amount,venue,meal,signature,id -> runTask {
                            val result=Api(server,token).call("/cash",JSONObject().put("department",selectedLocation).put("amount",amount).put("venue",venue).put("meal",meal).put("signature",signature).put("request_id",id));finishEntry(result.optBoolean("queued"))
                        } }
                        "Equipment" -> EquipmentScreen(user,dashboard.optJSONObject("shift"),selectedLocation,busy) { action,radio,keys,signature,id -> runTask {
                            val result=Api(server,token).call("/equipment",JSONObject().put("action",action).put("location",selectedLocation).put("radio",radio).put("keys",keys).put("signature",signature).put("request_id",id));finishEntry(result.optBoolean("queued"))
                        } }
                        "History" -> {
                            Panel("Today’s activity", "All times are Central time") {
                                val cash=dashboard.optJSONArray("cash") ?: JSONArray();val events=dashboard.optJSONArray("events") ?: JSONArray()
                                if(cash.length()+events.length()==0) Text("No logs recorded today.")
                                for(i in 0 until cash.length()) {val r=cash.getJSONObject(i);Text("${r.getString("name")} • ${r.getString("department")} • ${r.optString("venue")} ${r.optString("meal")} • $${"%.2f".format(r.getInt("cents")/100.0)}");Text(localTime(r.getString("created")),style=MaterialTheme.typography.bodySmall);HorizontalDivider()}
                                for(i in 0 until events.length()) {val r=events.getJSONObject(i);Text("${r.getString("name")} • ${r.getString("action").replace('_',' ')}");Text("Radio ${r.getString("radio")} / Keys ${r.getString("keys")} • ${localTime(r.getString("created"))}",style=MaterialTheme.typography.bodySmall);HorizontalDivider()}
                            }
                            Panel("Equipment status", "Outstanding radios and keys") {
                                val active=dashboard.optJSONArray("active") ?: JSONArray()
                                if(active.length()==0) Text("All shifts closed.")
                                for(i in 0 until active.length()) {val r=active.getJSONObject(i);Text("${r.getString("name")} • ${if(r.getString("state") in listOf("lunch_out","out")) "Out · equipment returned" else "Radio ${r.getString("radio")} / Keys ${r.getString("keys")}"}")}
                            }
                            Row {TextButton(onClick={runTask {refresh()}},enabled=!busy) {Text("Refresh")};TextButton(onClick={page="AdminHome"}) {Text("Back to admin")}}
                        }
                        "Settings" -> {
                            Panel("Report email settings", "Daily cash report • 4:00 AM • America/Chicago") {
                                OutlinedTextField(email,{email=it},label={Text("Report email address")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())
                                Row(verticalAlignment=Alignment.CenterVertically) {Switch(enabled,{enabled=it},enabled=!busy);Text("Send daily cash report",Modifier.padding(12.dp))}
                                Button(onClick={runTask {val recipient=email.trim();Api(server,token).call("/settings",JSONObject().put("email",recipient).put("enabled",enabled));email=recipient;message="Report settings saved."}},enabled=!busy) {Text("Save settings")}
                                OutlinedButton(onClick={runTask {Api(server,token).call("/test-email",JSONObject());message="Test email sent to the saved address."}},enabled=!busy) {Text("Send test email")}
                                OutlinedButton(onClick={runTask {val result=Api(server,token).call("/reports/previous",JSONObject());message="Report for ${result.getString("date")} sent to the saved address."}},enabled=!busy) {Text("Send yesterday’s cash report")}
                                Text("Save changes before sending. Email delivery needs Firebase Cloud Functions and the email provider configured.",style=MaterialTheme.typography.bodySmall)
                            }
                            Panel("Email a report", "Select one day or a date range. Equipment reports are sent only when requested.") {
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    listOf("cash","equipment").forEach {type->FilterChip(selected=reportType==type,onClick={reportType=type},enabled=!busy,label={Text(if(type=="cash") "Cash" else "Radio & keys")})}
                                }
                                FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    DateField("From",reportStart,!busy) {reportStart=it;if(reportEnd<it)reportEnd=it}
                                    DateField("Through",reportEnd,!busy) {reportEnd=it}
                                }
                                Button(onClick={runTask {Api(server,token).call("/reports/range",JSONObject().put("type",reportType).put("start_date",reportStart.toString()).put("end_date",reportEnd.toString()));message="Report sent to the saved email address."}},enabled=!busy && reportEnd>=reportStart) {Text("Send selected report")}
                                Text("Both dates are included, in Central time. Reports include CSV data and an HTML attachment with signatures.",style=MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick={page="AdminHome"},enabled=!busy) {Text("Back to admin")}
                        }
                    }
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(message.isNotEmpty()) Text(message,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun TabletHome(welcome: Boolean,message: String,onStart:()->Unit,onChoose:(String)->Unit,onAdmin:()->Unit) {
    val navy=Color(0xFF102A43)
    BoxWithConstraints(Modifier.fillMaxSize().background(if(welcome) navy else Color(0xFFF3F6FB)).safeDrawingPadding()) {
        val compact=this.maxWidth<600.dp || this.maxHeight<480.dp
        val viewportHeight=this.maxHeight
        Column(Modifier.fillMaxWidth().heightIn(min=viewportHeight).verticalScroll(rememberScrollState()).padding(if(compact) 20.dp else 32.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp,Alignment.CenterVertically)) {
            Column(Modifier.widthIn(max=820.dp).fillMaxWidth()) {
                Text("DropLog · ${BuildConfig.VERSION_NAME}",fontSize=if(compact) 24.sp else 28.sp,fontWeight=FontWeight.Bold,color=if(welcome) Color.White else navy)
                Text("BEST WESTERN SWISS CLOCK INN",fontSize=12.sp,color=if(welcome) Color(0xFFB9CBDE) else Color(0xFF64748B))
            }
            Text(if(welcome) "A smooth shift starts here." else "What would you like to log?",fontSize=if(compact) 30.sp else 42.sp,fontWeight=FontWeight.Bold,color=if(welcome) Color.White else navy,textAlign=TextAlign.Center,modifier=Modifier.widthIn(max=820.dp))
            Text(if(welcome) "Cash drops. Radio & keys. Housekeeping meals." else "Choose a log, then enter your employee PIN.",fontSize=if(compact) 18.sp else 20.sp,color=if(welcome) Color(0xFFB9CBDE) else Color(0xFF64748B),textAlign=TextAlign.Center)
            if(welcome)Button(onClick=onStart,modifier=Modifier.widthIn(max=300.dp).fillMaxWidth().heightIn(min=72.dp),shape=RoundedCornerShape(20.dp)){Text("Let’s start",fontSize=24.sp)}
            else {
                Button(onClick={onChoose("Cash")},modifier=Modifier.widthIn(max=440.dp).fillMaxWidth().heightIn(min=72.dp)){Text("Cash",fontSize=24.sp)}
                Button(onClick={onChoose("Equipment")},modifier=Modifier.widthIn(max=440.dp).fillMaxWidth().heightIn(min=72.dp)){Text("Equipment",fontSize=24.sp)}
            }
            if(message.isNotEmpty())Text(message,color=if(welcome) Color.White else navy,textAlign=TextAlign.Center)
            TextButton(onClick=onAdmin){Text("Admin recovery · Email sign-in",color=if(welcome) Color(0xFFB9CBDE) else navy)}
        }
    }
}

@Composable
fun AdaptiveFormRow(content: @Composable FlowRowScope.()->Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth(),maxItemsInEachRow=if(this.maxWidth<500.dp) 1 else 2,horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
    }
}

@Composable
fun PinScreen(destination: String,pin: String,busy: Boolean,onChange:(String)->Unit,onContinue:()->Unit) {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("$destination • Enter your PIN",fontSize=28.sp,fontWeight=FontWeight.Bold)
        Text("Your 3–8 digit PIN identifies your employee account.")
        Text(if(pin.isEmpty()) "— — —" else "● ".repeat(pin.length),fontSize=30.sp,modifier=Modifier.padding(8.dp))
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("Clear","0","⌫")).forEach { row ->
            Row(Modifier.widthIn(max=440.dp).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach { digit -> OutlinedButton(onClick={onChange(when(digit) {"Clear"->"";"⌫"->pin.dropLast(1);else->if(pin.length<8) pin+digit else pin})},enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=58.dp),shape=RoundedCornerShape(14.dp)) {Text(digit,fontSize=22.sp)} }
            }
        }
        Button(onClick=onContinue,enabled=!busy && pin.length in 3..8,modifier=Modifier.widthIn(max=440.dp).fillMaxWidth().heightIn(min=58.dp)) {Text("Continue",fontSize=20.sp)}
    }
}

data class WorkingShift(val label: String,val location: String,val duty: String)
fun stringList(array: JSONArray?): List<String> = if(array==null) emptyList() else (0 until array.length()).map {array.getString(it)}
fun workingShifts(user: JSONObject,shift: JSONObject?,destination: String = "All"): List<WorkingShift> {
    val manager=user.optString("role")=="manager"
    val locations=if(manager) listOf("Hotel","Restaurant") else stringList(user.optJSONArray("locations")).ifEmpty {listOf(user.optString("location","Hotel"))}
    val duties=if(manager) listOf("hotel_cash","restaurant_cash","equipment") else stringList(user.optJSONArray("duties"))
    val choices=mutableListOf<WorkingShift>()
    if("hotel_cash" in duties && "Hotel" in locations)choices.add(WorkingShift("Hotel · Front desk cash","Hotel","hotel_cash"))
    if(duties.any {it in listOf("restaurant_cash","bar_cash")} && "Restaurant" in locations)choices.add(WorkingShift("Restaurant · ${cashOutlets(user).joinToString(" / ")} cash","Restaurant","restaurant_cash"))
    if("equipment" in duties) {
        val active=shift!=null && shift.optString("state")!="end"
        locations.filter {it=="Hotel" && (!active || it==shift?.optString("location",locations.first()))}.forEach {choices.add(WorkingShift("$it · Housekeeping radio & keys",it,"equipment"))}
    }
    return choices.filter { destination=="All" || (if(it.duty=="equipment") "Equipment" else "Cash")==destination }
}

@Composable
fun ChoiceDropdown(label: String,options: List<String>,selected: String,busy: Boolean,onSelect:(String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={expanded=true},enabled=!busy && options.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("$label: ${selected.ifBlank {"Select"}}  ▾")}
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach {value->DropdownMenuItem(text={Text(value)},onClick={expanded=false;onSelect(value)})}
        }
    }
}

@Composable
fun WorkingShiftScreen(user: JSONObject,shift: JSONObject?,destination: String,busy: Boolean,onContinue:(WorkingShift)->Unit) {
    val choices=workingShifts(user,shift,destination)
    var selected by remember(user.toString(),shift?.toString(),destination) {mutableStateOf(choices.firstOrNull()?.label ?: "")}
    Panel("Hello, ${user.optString("name")}", "Choose the shift you are working before recording a log") {
        ChoiceDropdown("Working shift",choices.map {it.label},selected,busy) {selected=it}
        Button(onClick={choices.firstOrNull {it.label==selected}?.let(onContinue)},enabled=!busy && selected.isNotEmpty(),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Continue to my log")}
        if(choices.isEmpty())Text("You do not have access to $destination logs. Ask your admin to update your duties, or sign out and choose another log.")
    }
}

@Composable
fun DateField(label: String,value: LocalDate,enabled: Boolean,onChange:(LocalDate)->Unit) {
    val context=LocalContext.current
    OutlinedButton(onClick={DatePickerDialog(context,{_,year,month,day->onChange(LocalDate.of(year,month+1,day))},value.year,value.monthValue-1,value.dayOfMonth).show()},enabled=enabled) {Text("$label: $value")}
}

@Composable
fun Panel(title: String, subtitle: String, content: @Composable ColumnScope.()->Unit) {
    Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color.White),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(subtitle,style=MaterialTheme.typography.bodySmall,color=Color(0xFF657086))
            content()
        }
    }
}

@Composable
fun CashScreen(user: JSONObject,department: String,busy: Boolean,submit:(String,String,String,JSONArray,String)->Unit) {
    val outlets=cashOutlets(user)
    var amount by remember {mutableStateOf("")};var venue by remember {mutableStateOf(outlets.firstOrNull() ?: "Restaurant")};var meal by remember {mutableStateOf(if(venue=="Bar") "Lunch" else "Breakfast")}
    var signature by remember {mutableStateOf(JSONArray())};var confirm by remember {mutableStateOf(false)}
    val requestId=remember(department,amount,venue,meal,signature.toString()) {UUID.randomUUID().toString()}
    Panel("${user.optString("name")} · $department cash", "Your full name, date and time are recorded automatically") {
        if(workingShifts(user,null).size>1)Text("Working shift: $department cash",color=MaterialTheme.colorScheme.secondary)
        if(department=="Restaurant") {
            ChoiceDropdown("Outlet",outlets,venue,busy) {venue=it;if(venue=="Bar" && meal=="Breakfast")meal="Lunch"}
            ChoiceDropdown("Meal",if(venue=="Bar")listOf("Lunch","Dinner") else listOf("Breakfast","Lunch","Dinner"),meal,busy) {meal=it}
        }
        OutlinedTextField(amount,{amount=it},label={Text("Cash amount ($)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),enabled=!busy,modifier=Modifier.fillMaxWidth())
        Text("Employee signature",fontWeight=FontWeight.Bold)
        SignaturePad(enabled=!busy) {signature=it}
        Button(onClick={confirm=true},enabled=!busy && signature.length()>0 && amount.toBigDecimalOrNull()?.let {it.signum()>0 && it.scale()<=2 && it<=java.math.BigDecimal("1000000")}==true,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text("Review & save cash")}
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Confirm cash drop")},text={Text("${user.optString("name")}\n$department${if(department=="Restaurant") " · $venue · $meal" else ""}\nAmount: $$amount")},confirmButton={TextButton(onClick={confirm=false;submit(amount,venue,meal,signature,requestId)}) {Text("Save signed cash log")}},dismissButton={TextButton(onClick={confirm=false}) {Text("Cancel")}})
}

@Composable
fun EquipmentScreen(user: JSONObject,shift: JSONObject?,location: String,busy: Boolean,submit:(String,String,String,JSONArray,String)->Unit) {
    val state=shift?.optString("state")?.takeIf { it in listOf("start","in","out","lunch_in","lunch_out","end") } ?: "ready"
    val activeShift=shift!=null && state in listOf("start","in","out","lunch_in","lunch_out")
    var action by remember(state) {mutableStateOf(when(state){"ready"->"start";"in","start","lunch_in"->"out";"end"->"start";else->"in"})}
    var radio by remember(state,action) {mutableStateOf("")};var keys by remember(state,action) {mutableStateOf("")}
    var signature by remember(state,action) {mutableStateOf(JSONArray())}
    var confirmEnd by remember {mutableStateOf(false)}
    val requestId=remember(state,action,radio,keys,signature.toString()) {UUID.randomUUID().toString()}
    val checkout=action=="start" || action=="in"
    Panel("${user.optString("name")} · Radio & keys", "${if(state=="end" && action=="undo_end") shift?.optString("location",location) else location} · ${when(state){"ready"->"Ready to start";"end"->"Shift ended";"in","start","lunch_in"->"Equipment checked out";else->"Equipment returned"}}") {
        if(state=="end") {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=action=="start",onClick={action="start"},enabled=!busy,label={Text("Start new shift")})
                FilterChip(selected=action=="undo_end",onClick={action="undo_end"},enabled=!busy && location==shift?.optString("location",location),label={Text("Undo end shift")})
            }
            if(action=="undo_end")Text("Reopen your last shift with equipment marked returned. Sign below, then select In on your next login to collect equipment.")
        } else if(activeShift) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=action=="in",onClick={action="in"},enabled=!busy && state in listOf("out","lunch_out"),label={Text("In · Collect")})
                FilterChip(selected=action=="out",onClick={action="out"},enabled=!busy && state in listOf("in","start","lunch_in"),label={Text("Out · Return")})
                if(action!="end")OutlinedButton(onClick={confirmEnd=true},enabled=!busy) {Text("End shift")}
            }
        }
        if(checkout) {
            OutlinedTextField(radio,{radio=it.filter {c->c in '0'..'9'}.take(6)},label={Text("Radio number")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(keys,{keys=it.filter {c->c in '0'..'9'}.take(6)},label={Text("Key set number")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),enabled=!busy,singleLine=true,modifier=Modifier.fillMaxWidth())
        } else if(action!="undo_end")Text("Radio ${shift?.optString("radio")} · Keys ${shift?.optString("keys")}${if(state in listOf("out","lunch_out")) " · already returned" else " · return before signing"}")
        if(action=="end" && activeShift) {
            Text("End shift selected. Sign below to finish, or undo to continue your shift.")
            OutlinedButton(onClick={action=if(state in listOf("in","start","lunch_in")) "out" else "in"},enabled=!busy) {Text("Undo end shift")}
        }
        Text("Employee signature",fontWeight=FontWeight.Bold)
        key(state,action) {SignaturePad(enabled=!busy) {signature=it}}
        Button(onClick={submit(action,radio,keys,signature,requestId)},enabled=!busy && signature.length()>0 && (!checkout || radio.isNotBlank() && keys.isNotBlank()),modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text(when(action){"end"->"Sign & end shift";"undo_end"->"Sign & reopen shift";"out"->"Sign & return equipment";else->"Sign & collect equipment"})}
    }
    if(confirmEnd)AlertDialog(onDismissRequest={confirmEnd=false},title={Text("Are you sure to End the Shift?")},text={Text("Return your radio and keys. You will then sign to confirm ending your shift.")},confirmButton={TextButton(onClick={confirmEnd=false;action="end"}) {Text("Yes, end shift")}},dismissButton={TextButton(onClick={confirmEnd=false}) {Text("Continue shift")}})
}

@Composable
fun SignaturePad(enabled: Boolean = true,onChange:(JSONArray)->Unit) {
    val strokes=remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf(listOf<Offset>()) }
    fun publish() {
        val array=JSONArray()
        strokes.forEach { stroke -> val points=JSONArray(); stroke.forEach { p-> points.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }; array.put(points) }
        onChange(array)
    }
    Canvas(Modifier.fillMaxWidth().height(150.dp).background(Color(0xFFF0F3F8),RoundedCornerShape(12.dp)).pointerInput(enabled) {
        if(!enabled)return@pointerInput
        fun normalized(p: Offset)=Offset((p.x/size.width).coerceIn(0f,1f),(p.y/size.height).coerceIn(0f,1f))
        detectDragGestures(onDragStart={current=listOf(normalized(it))},onDragEnd={if(current.size>=3 && strokes.size<30) { strokes.add(current); publish() };current=emptyList()},onDragCancel={current=emptyList()}) { change,_ -> change.consume(); if(current.size<2000) current=current+normalized(change.position) }
    }) {
        (strokes.toList()+listOf(current)).forEach { stroke -> stroke.zipWithNext().forEach { (a,b)-> drawLine(Color(0xFF172B4D),Offset(a.x*size.width,a.y*size.height),Offset(b.x*size.width,b.y*size.height),strokeWidth=4f) } }
    }
    TextButton(onClick={strokes.clear();current=emptyList();publish()},enabled=enabled) { Text("Clear signature") }
}


@Composable
fun EmployeeActions(user: JSONObject,busy: Boolean,onCash:()->Unit,onEquipment:()->Unit,onMeal:()->Unit,@Suppress("UNUSED_PARAMETER") onHousekeeping:()->Unit) {
    val choices=workingShifts(user,null)
    val housekeeping=choices.any {it.duty=="equipment"}
    Panel("Hello, ${user.optString("name")}", "Choose what you would like to log for your shift") {
        if(!housekeeping && choices.any {it.duty!="equipment"})Button(onClick=onCash,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=68.dp)){Text("Drop cash",fontSize=22.sp)}
        if(housekeeping){
            Button(onClick=onEquipment,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=68.dp)){Text("Log equipment",fontSize=22.sp)}
            Button(onClick=onMeal,enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=68.dp)){Text(if(user.optString("employment_type")=="ortiz") "Log Ortiz meal" else "Log meal",fontSize=22.sp)}
        }
        if(choices.isEmpty())Text("No log duties are assigned. Ask an admin to update your account.")
    }
}

@Composable
fun MealScreen(user: JSONObject,meals: JSONArray,busy: Boolean,submit:(String,JSONArray,String)->Unit) {
    var now by remember {mutableStateOf(Instant.now())}
    LaunchedEffect(Unit){while(true){delay(1000);now=Instant.now()}}
    val meal=OfflineStore.mealAt(now)
    val today=now.atZone(ZoneId.of("America/Chicago")).toLocalDate().toString()
    val uid=user.optString("id")
    val own=(0 until meals.length()).map {meals.getJSONObject(it)}.filter {it.optString("user_id")==uid && (it.optString("day")==today || it.optString("created").take(10)==today)}
    val already=own.any {it.optString("meal")==meal};val eligible=own.size<2&&!already
    var signature by remember {mutableStateOf(JSONArray())}
    val id=remember(signature.toString()){UUID.randomUUID().toString()}
    Panel("${user.optString("name")} · $meal", "${localTime(now.toString())} · Central time") {
        Text("${own.size} of 2 free meals logged today")
        Text("Breakfast before noon · Lunch noon–2:59 PM · Dinner from 3 PM",style=MaterialTheme.typography.bodySmall)
        if(!eligible)Text(if(already)"You have already logged $meal today." else "You have used your two free meals today.")
        else {
            Text("Sign to confirm you are taking this meal.",fontWeight=FontWeight.Bold)
            SignaturePad(enabled=!busy){signature=it}
            Button(onClick={submit(meal,signature,id)},enabled=!busy&&signature.length()>0,modifier=Modifier.fillMaxWidth().height(60.dp)){Text("Sign & log $meal")}
        }
    }
}

@Composable
fun CreateAdminScreen(busy: Boolean,onSave:(JSONObject)->Unit) {
    var first by remember {mutableStateOf("")};var last by remember {mutableStateOf("")}
    var email by remember {mutableStateOf("")};var password by remember {mutableStateOf("")};var confirm by remember {mutableStateOf("")}
    Panel("Add admin", "Each admin signs in with their own email and password. Admin setup requires internet.") {
        OutlinedTextField(first,{first=it.take(50)},label={Text("First name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(last,{last=it.take(50)},label={Text("Last name")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(email,{email=it},label={Text("Admin email")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(password,{password=it},label={Text("Password (at least 12 characters)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
        OutlinedTextField(confirm,{confirm=it},label={Text("Confirm password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
        Button(onClick={onSave(JSONObject().put("first_name",first.trim()).put("last_name",last.trim()).put("email",email.trim()).put("password",password))},enabled=!busy&&first.isNotBlank()&&last.isNotBlank()&&email.contains("@")&&password.length>=12&&password==confirm){Text("Create admin")}
    }
}

@Composable
fun ReportRow(cells: List<String>) {
    Row(Modifier.fillMaxWidth().padding(vertical=8.dp)) {cells.forEach {Text(it,Modifier.weight(1f).padding(horizontal=4.dp))}}
    HorizontalDivider()
}

@Composable
fun SavedSignature(value: String) {
    val strokes=remember(value){try {JSONArray(value)}catch(_: Exception){JSONArray()}}
    if(strokes.length()==0)Text("Signature unavailable")
    else Canvas(Modifier.fillMaxWidth().height(90.dp).background(Color(0xFFF1F5F9))) {
        for(i in 0 until strokes.length()) {
            val points=strokes.optJSONArray(i) ?: continue
            for(j in 1 until points.length()) {
                val a=points.optJSONArray(j-1) ?: continue;val b=points.optJSONArray(j) ?: continue
                drawLine(Color(0xFF172B4D),Offset(a.optDouble(0).toFloat()*size.width,a.optDouble(1).toFloat()*size.height),Offset(b.optDouble(0).toFloat()*size.width,b.optDouble(1).toFloat()*size.height),strokeWidth=3f)
            }
        }
    }
}

@Composable
fun ActivityTable(type: String, data: JSONArray, servers: JSONArray) {
    val rows=(0 until data.length()).map {data.getJSONObject(it)}
    if(rows.isEmpty())Text("No records in the selected range.")
    if(type=="meal") {
        Text("Meal counts by employee",fontWeight=FontWeight.Bold)
        ReportRow(listOf("Employee","Breakfast","Lunch","Dinner","Total"))
        rows.groupBy {it.optString("user_id")}.values.forEach {meals->
            ReportRow(listOf(meals.first().optString("name"),meals.count {it.optString("meal")=="Breakfast"}.toString(),meals.count {it.optString("meal")=="Lunch"}.toString(),meals.count {it.optString("meal")=="Dinner"}.toString(),meals.size.toString()))
        }
        Text("Date-wise meal counts",fontWeight=FontWeight.Bold)
        ReportRow(listOf("Date","Employee","Breakfast","Lunch","Dinner"))
        rows.groupBy {it.optString("day") to it.optString("user_id")}.toSortedMap(compareBy<Pair<String,String>> {it.first}.thenBy {it.second}).forEach {(key,meals)->
            ReportRow(listOf(key.first,meals.first().optString("name"),meals.count {it.optString("meal")=="Breakfast"}.toString(),meals.count {it.optString("meal")=="Lunch"}.toString(),meals.count {it.optString("meal")=="Dinner"}.toString()))
        }
        val groups=rows.groupBy {it.optString("day") to it.optString("meal")}
        val serverRows=(0 until servers.length()).map {servers.getJSONObject(it)}
        (groups.keys+serverRows.map {it.optString("day") to it.optString("meal")}).distinct().sortedWith(compareBy<Pair<String,String>> {it.first}.thenBy {it.second}).forEach {key->
            val meals=groups[key] ?: emptyList()
            val name=serverRows.find {it.optString("day")==key.first&&it.optString("meal")==key.second}?.optString("name") ?: meals.firstOrNull()?.optString("server_name").orEmpty()
            Text("Server Name: ${name.ifBlank {"Not recorded"}} · Date: ${key.first} · ${key.second}",fontWeight=FontWeight.Bold)
            Text("Number of employees who logged meal: ${meals.map {it.optString("user_id")}.distinct().size}")
            ReportRow(listOf("Employee","Employment type","Signature"))
            meals.forEach {r->
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(r.optString("name"),Modifier.weight(1f))
                    Text(if(r.optString("employment_type")=="ortiz") "Ortiz" else "Full time",Modifier.weight(1f))
                    Box(Modifier.weight(1f)){SavedSignature(r.optString("signature"))}
                };HorizontalDivider()
            }
        }
    } else {
        ReportRow(if(type=="cash")listOf("Date / time","Employee","Location","Amount") else listOf("Date / time","Employee","Action","Radio / keys"))
        rows.forEach {r->ReportRow(if(type=="cash")listOf(localTime(r.optString("created")),r.optString("name"),r.optString("department"),"$${"%.2f".format(r.optInt("cents")/100.0)}") else listOf(localTime(r.optString("created")),r.optString("name"),r.optString("action"),"${r.optString("radio")} / ${r.optString("keys")}"))}
    }
}
