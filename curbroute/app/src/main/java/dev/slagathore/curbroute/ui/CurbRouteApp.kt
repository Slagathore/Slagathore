package dev.slagathore.curbroute.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.slagathore.curbroute.ReviewStop
import dev.slagathore.curbroute.RouteViewModel
import dev.slagathore.curbroute.Settings
import dev.slagathore.curbroute.core.Objective
import dev.slagathore.curbroute.core.PlannedStop
import dev.slagathore.curbroute.core.RoutePlan
import dev.slagathore.curbroute.core.nav.GoogleMapsLinks
import dev.slagathore.curbroute.platform.MapsLauncher
import dev.slagathore.curbroute.platform.Photos
import dev.slagathore.curbroute.platform.StartLocation
import java.util.Locale

private val Warn = Color(0xFFE8710A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurbRouteApp(vm: RouteViewModel) {
    val context = LocalContext.current
    val session = vm.session
    val plan = session.plan
    var showSettings by remember { mutableStateOf(false) }

    // --- system pickers / permission prompts (each returns its answer to the lambda) ---
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        if (uris.isNotEmpty()) vm.addPages(uris)
    }
    var pendingPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhoto
        if (ok && uri != null) vm.addPages(listOf(uri))
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.optimize() // granted or not: without GPS the optimizer just picks the best first stop itself
    }
    val addFromCamera = {
        val uri = Photos.newPhotoUri(context)
        pendingPhoto = uri
        takePhoto.launch(uri)
    }
    val addFromGallery = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val optimize = {
        if (vm.settings.startFromGps && !StartLocation.hasPermission(context)) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            vm.optimize()
        }
    }

    BackHandler(enabled = plan != null) { vm.backToReview() }

    CurbRouteTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (plan != null) "Today's route" else "CurbRoute") },
                    navigationIcon = {
                        if (plan != null) {
                            IconButton(onClick = vm::backToReview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to stops") }
                        }
                    },
                    actions = {
                        if (session.stops.isNotEmpty()) {
                            IconButton(onClick = vm::newDay) { Icon(Icons.Default.Refresh, "Start a new day") }
                        }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Settings") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                vm.busy?.let { message ->
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    plan != null -> ResultScreen(plan, session.done, vm::markDone) { url -> MapsLauncher.open(context, url) }
                    session.stops.isNotEmpty() -> ReviewScreen(
                        stops = session.stops,
                        warnings = session.warnings,
                        enabled = vm.busy == null,
                        onToggle = vm::toggle,
                        onEdit = vm::edit,
                        onRemove = vm::remove,
                        onAddManual = vm::addManual,
                        onAddCamera = addFromCamera,
                        onAddGallery = addFromGallery,
                        onOptimize = optimize,
                    )
                    else -> CaptureScreen(enabled = vm.busy == null, onCamera = addFromCamera, onGallery = addFromGallery)
                }
            }
        }

        vm.error?.let { message ->
            AlertDialog(
                onDismissRequest = vm::dismissError,
                confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                title = { Text("Something went wrong") },
                text = { Text(message) },
            )
        }
        if (showSettings) {
            SettingsDialog(vm.settings, onDismiss = { showSettings = false }) { vm.saveSettings(it); showSettings = false }
        }
    }
}

// ------------------------------------------------------------------------------------------------------
// Screen 1: take/choose photos

@Composable
private fun CaptureScreen(enabled: Boolean, onCamera: () -> Unit, onGallery: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Snap your route sheet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Flat, well lit, whole page in frame. 1-3 pages. The photo is read on the phone and never " +
                "uploaded. Only the addresses are sent out to find them on the map.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCamera, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Take photo of a page") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onGallery, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Choose photos (up to 3)") }
    }
}

// ------------------------------------------------------------------------------------------------------
// Screen 2: check what OCR found, fix mistakes, uncheck skips

@Composable
private fun ReviewScreen(
    stops: List<ReviewStop>,
    warnings: List<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onEdit: (String, String) -> Boolean,
    onRemove: (String) -> Unit,
    onAddManual: (String) -> Boolean,
    onAddCamera: () -> Unit,
    onAddGallery: () -> Unit,
    onOptimize: () -> Unit,
) {
    var editing by remember { mutableStateOf<ReviewStop?>(null) }
    var adding by remember { mutableStateOf(false) }
    val included = stops.count { it.included }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (warnings.isNotEmpty()) {
                item { WarningCard(warnings) }
            }
            item {
                Text(
                    "Found ${stops.size} stops. Tap one to fix a misread address, uncheck skips.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            itemsIndexed(stops, key = { _, r -> r.stop.dedupeKey }) { _, r ->
                Card(Modifier.fillMaxWidth().clickable { editing = r }) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.included, onCheckedChange = { onToggle(r.stop.dedupeKey) })
                        Column(Modifier.weight(1f)) {
                            Text(r.stop.label, fontWeight = FontWeight.SemiBold)
                            val details = listOfNotNull(r.stop.city, r.stop.appointment?.substringAfter(' '), r.stop.customerName)
                            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.Default.Edit, contentDescription = "Edit", Modifier.padding(8.dp))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddCamera, enabled = enabled) { Text("+ Page (camera)") }
                    OutlinedButton(onClick = onAddGallery, enabled = enabled) { Text("+ Page (photos)") }
                }
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null)
                    Text("Type in a stop")
                }
            }
        }
        Button(
            onClick = onOptimize,
            enabled = enabled && included > 0,
            modifier = Modifier.fillMaxWidth().padding(12.dp).height(52.dp),
        ) { Text("Optimize $included stops (house on the right)") }
    }

    editing?.let { r ->
        AddressDialog(
            title = "Fix address",
            initial = r.stop.singleLine,
            onDismiss = { editing = null },
            onRemove = { onRemove(r.stop.dedupeKey); editing = null },
        ) { text -> onEdit(r.stop.dedupeKey, text).also { if (it) editing = null } }
    }
    if (adding) {
        AddressDialog(title = "Add a stop", initial = "", onDismiss = { adding = false }) { text ->
            onAddManual(text).also { if (it) adding = false }
        }
    }
}

@Composable
private fun AddressDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onRemove: (() -> Unit)? = null,
    onSave: (String) -> Boolean,
) {
    var text by remember { mutableStateOf(initial) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; invalid = false },
                    label = { Text("123 Street Name, City, TX 76063") },
                    isError = invalid,
                )
                if (invalid) Text("Needs house number, street, city, state and ZIP.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { invalid = !onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                onRemove?.let { TextButton(onClick = it) { Text("Delete stop") } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ------------------------------------------------------------------------------------------------------
// Screen 3: the optimized route

@Composable
private fun ResultScreen(plan: RoutePlan, done: Set<String>, onDone: (String) -> Unit, openUrl: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { SummaryCard(plan, openUrl) }
        val problems = plan.warnings + plan.unresolved.map { "Couldn't find ${it.singleLine}. Add it by hand or drive it yourself." }
        if (problems.isNotEmpty()) item { WarningCard(problems) }
        itemsIndexed(plan.stops, key = { _, p -> p.sheet.dedupeKey }) { i, p ->
            StopRow(i + 1, p, p.sheet.dedupeKey in done, onDone = { onDone(p.sheet.dedupeKey) }) {
                // Navigate to just this stop, through its lead-in point if it has one.
                val points = listOfNotNull(
                    p.leadIn?.let { GoogleMapsLinks.Point(it.toLatLngString(), isLeadIn = true) },
                    GoogleMapsLinks.Point(p.sheet.singleLine),
                )
                openUrl(GoogleMapsLinks.toUrl(points))
            }
        }
    }
}

@Composable
private fun SummaryCard(plan: RoutePlan, openUrl: (String) -> Unit) {
    val saved = plan.sheetOrderMeters - plan.totalMeters
    val forced = plan.stops.count { it.leadIn != null }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                String.format(Locale.US, "%.1f km · %.0f min driving", plan.totalMeters / 1000, plan.totalSeconds / 60),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
            if (plan.sheetOrderMeters > 0) {
                Text(
                    String.format(
                        Locale.US, "Paper order, same passenger-side rule: %.1f km. Saves %.1f km (%.0f%%).",
                        plan.sheetOrderMeters / 1000, saved / 1000, 100 * saved / plan.sheetOrderMeters,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (forced > 0) {
                Text(
                    "$forced stop(s) get an extra waypoint just before the house so Google Maps brings you in " +
                        "passenger-side. Maps will say \"arrived\" there. Keep rolling.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            plan.mapsLinks.forEachIndexed { i, url ->
                Button(onClick = { openUrl(url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (plan.mapsLinks.size == 1) "Start in Google Maps" else "Google Maps part ${i + 1} of ${plan.mapsLinks.size}")
                }
            }
        }
    }
}

@Composable
private fun StopRow(number: Int, stop: PlannedStop, isDone: Boolean, onDone: () -> Unit, onNavigate: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).background(
                    if (isDone) Color.Gray else MaterialTheme.colorScheme.primary,
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) { Text("$number", color = Color.White, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    stop.sheet.label,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isDone) Color.Gray else Color.Unspecified,
                )
                val leg = if (stop.legMeters > 0) String.format(Locale.US, "%.2f km", stop.legMeters / 1000) else "start"
                Text(listOfNotNull(leg, stop.sheet.appointment?.substringAfter(' ')).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                if (stop.leadIn != null) {
                    Text(
                        "Forced approach",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp).background(Warn, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            IconButton(onClick = onNavigate) { Icon(Icons.Default.Place, "Navigate to this stop") }
            Checkbox(checked = isDone, onCheckedChange = { onDone() })
        }
    }
}

// ------------------------------------------------------------------------------------------------------
// Shared bits

@Composable
private fun WarningCard(lines: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = Warn.copy(alpha = 0.15f))) {
        Column(Modifier.padding(12.dp)) {
            lines.forEach { line ->
                Row {
                    Icon(Icons.Default.Warning, null, tint = Warn, modifier = Modifier.size(18.dp))
                    Text(line, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(current: Settings, onDismiss: () -> Unit, onSave: (Settings) -> Unit) {
    var osrm by remember { mutableStateOf(current.osrmUrl) }
    var objective by remember { mutableStateOf(current.objective) }
    var gps by remember { mutableStateOf(current.startFromGps) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(osrm, { osrm = it }, label = { Text("Routing server (OSRM)") }, singleLine = true)
                Text(
                    "The public demo server is for testing only (non-commercial, rate limited). " +
                        "Point this at your own OSRM for daily use.",
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider()
                Text("Minimize", fontWeight = FontWeight.SemiBold)
                Objective.entries.forEach { o ->
                    Row(Modifier.fillMaxWidth().clickable { objective = o }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = objective == o, onClick = { objective = o })
                        Text(if (o == Objective.DISTANCE) "Distance (miles on the truck)" else "Drive time")
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start from my GPS location", Modifier.weight(1f))
                    Switch(checked = gps, onCheckedChange = { gps = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(Settings(osrm.trim(), objective, gps)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CurbRouteTheme(content: @Composable () -> Unit) {
    val green = Color(0xFF2E7D32)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF81C784), primaryContainer = Color(0xFF1B5E20))
    } else {
        lightColorScheme(primary = green, primaryContainer = Color(0xFFC8E6C9))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
