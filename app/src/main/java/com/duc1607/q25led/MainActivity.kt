package com.duc1607.q25led

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.duc1607.q25led.ui.theme.Q25LedTheme

// Define the app routes
sealed class Screen(val route: String) {
    object Home : Screen("home")
}

/**
 * Function to control the LED based on the provided color name
 * @param colorName The color to set the LED to, or "OFF" to turn it off
 */
fun ledControl(colorName: String) {
    // This function controls the physical LED by writing to the brightness files
    var red = 0
    var green = 0
    var blue = 0

    when (colorName.uppercase()) {
        "RED" -> {
            red = 255
            green = 0
            blue = 0
        }
        "GREEN" -> {
            red = 0
            green = 255
            blue = 0
        }
        "BLUE" -> {
            red = 0
            green = 0
            blue = 255
        }
        "YELLOW" -> {
            red = 255
            green = 255
            blue = 0
        }
        "CYAN" -> {
            red = 0
            green = 255
            blue = 255
        }
        "MAGENTA" -> {
            red = 255
            green = 0
            blue = 255
        }
        "ORANGE" -> {
            red = 255
            green = 128
            blue = 0
        }
        "OFF" -> {
            red = 0
            green = 0
            blue = 0
        }
        else -> {
            red = 0
            green = 0
            blue = 0
        }
    }

    try {
        // Create ProcessBuilder to execute shell commands with su (root) privileges
        val commands = arrayOf(
            arrayOf("su", "-c", "echo $red > /sys/class/leds/red/brightness"),
            arrayOf("su", "-c", "echo $green > /sys/class/leds/green/brightness"),
            arrayOf("su", "-c", "echo $blue > /sys/class/leds/blue/brightness")
        )

        for (command in commands) {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()

            // Wait for the process to complete
            process.waitFor()
        }
    } catch (e: Exception) {
        // Silent error handling
    }
}

data class AppInfo(
    val name: String,
    val packageName: String,
    val isSystemApp: Boolean,
    val isEnabled: Boolean = true,
    val ledColor: String = "BLUE" // Default LED color
)

// Available LED colors
val ledColors = listOf("RED", "GREEN", "BLUE", "YELLOW", "CYAN", "MAGENTA", "ORANGE")

// Function to save app and LED color preference
private fun saveAppPreference(context: Context, packageName: String, ledColor: String) {
    val sharedPrefs = context.getSharedPreferences("q25_led_prefs", Context.MODE_PRIVATE)
    sharedPrefs.edit().putString(packageName, ledColor).apply()
}

// Function to delete app preference
private fun deleteAppPreference(context: Context, packageName: String) {
    val sharedPrefs = context.getSharedPreferences("q25_led_prefs", Context.MODE_PRIVATE)
    sharedPrefs.edit().remove(packageName).apply()
}

// Function to load saved app preferences
private fun loadSavedApps(context: Context): List<AppInfo> {
    val sharedPrefs = context.getSharedPreferences("q25_led_prefs", Context.MODE_PRIVATE)
    val pm = context.packageManager
    val savedApps = mutableListOf<AppInfo>()

    // Get all preferences (stored as packageName -> ledColor)
    val allPrefs = sharedPrefs.all

    for ((packageName, ledColor) in allPrefs) {
        try {
            // Try to get the application info
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val name = pm.getApplicationLabel(appInfo).toString()
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

            savedApps.add(
                AppInfo(
                    name = name,
                    packageName = packageName,
                    isSystemApp = isSystemApp,
                    ledColor = ledColor as? String ?: "BLUE"
                )
            )
        } catch (e: Exception) {
            // App no longer installed, remove from preferences
            sharedPrefs.edit().remove(packageName).apply()
        }
    }

    return savedApps.sortedBy { it.name }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    var showAppDialog by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selectedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    // State to trigger recomposition when preferences change
    var preferencesVersion by remember { mutableStateOf(0) }

    // Function to delete an app and refresh the UI
    val deleteApp: (String) -> Unit = { packageName ->
        deleteAppPreference(context, packageName)
        // Increment to trigger recomposition and refresh the app list
        preferencesVersion++
    }

    // Load saved app preferences when the screen is first displayed or preferences change
    LaunchedEffect(preferencesVersion) {
        selectedApps = loadSavedApps(context)
    }

    // Use a Surface with the background color from the theme
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Q25 LED Controller",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Display selected apps
            if (selectedApps.isNotEmpty()) {
                Text(
                    text = "Selected Apps",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(selectedApps) { app ->
                        AppItem(app = app, onDelete = deleteApp)
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "No apps selected",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            Button(
                onClick = {
                    // Load installed apps when button is clicked
                    val pm = context.packageManager
                    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                        .map { appInfo ->
                            val name = pm.getApplicationLabel(appInfo).toString()
                            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            AppInfo(name, appInfo.packageName, isSystemApp)
                        }
                        .sortedBy { it.name }
                    installedApps = apps
                    showAppDialog = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Text("Add an app")
            }
        }
    }

    // App selection dialog
    if (showAppDialog) {
        Dialog(onDismissRequest = { showAppDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Select an App",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Load apps when dialog is shown - this ensures we have fresh data
                    LaunchedEffect(Unit) {
                        val pm = context.packageManager
                        val apps = pm.getInstalledPackages(PackageManager.GET_META_DATA)
                            .mapNotNull { packageInfo ->
                                // Use mapNotNull to handle null applicationInfo safely
                                val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null

                                try {
                                    val name = pm.getApplicationLabel(appInfo).toString()

                                    // More accurate way to determine if an app is a system app
                                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                                            (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                                    val isEnabled = appInfo.enabled

                                    AppInfo(
                                        name = name,
                                        packageName = packageInfo.packageName,
                                        isSystemApp = isSystemApp,
                                        isEnabled = isEnabled
                                    )
                                } catch (e: Exception) {
                                    // Skip apps that cause errors
                                    null
                                }
                            }
                            .filter { it.isEnabled } // Only show enabled apps
                            .sortedBy { it.name }

                        installedApps = apps
                    }

                    // Search box
                    var searchQuery by remember { mutableStateOf("") }
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        placeholder = { Text("Search apps") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        singleLine = true
                    )

                    // Better filtering of user apps
                    val filteredApps = installedApps
                        .filter { !it.isSystemApp || it.packageName.startsWith("com.android.vending") } // Include Play Store
                        .filter {
                            searchQuery.isEmpty() ||
                            it.name.contains(searchQuery, ignoreCase = true) ||
                            it.packageName.contains(searchQuery, ignoreCase = true)
                        }

                    // Show count of displayed apps
                    Text(
                        text = "Showing ${filteredApps.size} apps",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(filteredApps) { app ->
                            AppSelectItem(
                                app = app,
                                onClick = {
                                    // Add app to selected apps if not already added
                                    if (!selectedApps.contains(app)) {
                                        selectedApps = selectedApps + app

                                        // Save to preferences
                                        saveAppPreference(context, app.packageName, app.ledColor)
                                    }
                                    showAppDialog = false
                                }
                            )
                        }
                    }

                    Button(
                        onClick = { showAppDialog = false },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 8.dp)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun AppItem(app: AppInfo, onDelete: (String) -> Unit) {
    val context = LocalContext.current
    var showColorDialog by remember { mutableStateOf(false) }
    var currentColor by remember { mutableStateOf(app.ledColor) }

    // Update the color state when app.ledColor changes
    LaunchedEffect(app.ledColor) {
        currentColor = app.ledColor
    }

    // Get the background color based on the selected LED color
    val backgroundColor = when(currentColor.uppercase()) {
        "RED" -> androidx.compose.ui.graphics.Color(0xFFFF5252) // Bright red
        "GREEN" -> androidx.compose.ui.graphics.Color(0xFF4CAF50) // Pure green
        "BLUE" -> androidx.compose.ui.graphics.Color(0xFF2196F3) // Pure blue
        "YELLOW" -> androidx.compose.ui.graphics.Color(0xFFFFEB3B) // Bright yellow
        "CYAN" -> androidx.compose.ui.graphics.Color(0xFF00BCD4) // Pure cyan
        "MAGENTA" -> androidx.compose.ui.graphics.Color(0xFFE91E63) // Pink/magenta
        "ORANGE" -> androidx.compose.ui.graphics.Color(0xFFFF9800) // Pure orange
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    // Get the content color based on the background for proper contrast
    val contentColor = when(currentColor.uppercase()) {
        "RED", "GREEN", "BLUE", "MAGENTA", "ORANGE" -> androidx.compose.ui.graphics.Color.White
        "YELLOW", "CYAN" -> androidx.compose.ui.graphics.Color.Black
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { showColorDialog = true },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Delete button
                IconButton(
                    onClick = {
                        // Remove app from preferences
                        onDelete(app.packageName)
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = contentColor
                    )
                }
            }
        }
    }

    if (showColorDialog) {
        Dialog(onDismissRequest = { showColorDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Select LED Color for ${app.name}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                    ) {
                        items(ledColors) { color ->
                            ColorSelectItem(
                                color = color,
                                isSelected = currentColor == color,
                                onClick = {
                                    // Update local state immediately
                                    currentColor = color

                                    // Save to preferences
                                    saveAppPreference(context, app.packageName, color)

                                    showColorDialog = false
                                }
                            )
                        }
                    }

                    Button(
                        onClick = { showColorDialog = false },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 16.dp)
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun ColorIndicator(color: String) {
    // Background color based on the selected LED color
    val backgroundColor = when(color.uppercase()) {
        "RED" -> MaterialTheme.colorScheme.error
        "GREEN" -> androidx.compose.ui.graphics.Color.Green
        "BLUE" -> androidx.compose.ui.graphics.Color.Blue
        "YELLOW" -> androidx.compose.ui.graphics.Color(0xFFFFEB3B)
        "CYAN" -> androidx.compose.ui.graphics.Color(0xFF00BCD4)
        "MAGENTA" -> androidx.compose.ui.graphics.Color(0xFFE91E63)
        "ORANGE" -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        modifier = Modifier.size(24.dp),
        shape = MaterialTheme.shapes.small,
        color = backgroundColor
    ) { }
}

@Composable
fun ColorSelectItem(color: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ColorIndicator(color = color)

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = color,
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.weight(1f))

        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun AppSelectItem(app: AppInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Q25LedTheme {
                NotificationPermissionHandler {
                    HomeScreen()
                }
            }
        }
    }
}

/**
 * A composable that handles the notification permission request.
 * It wraps the content and shows a permission request dialog when needed.
 */
@Composable
fun NotificationPermissionHandler(content: @Composable () -> Unit) {
    val context = LocalContext.current
    
    // Check if we need to request notification permission (Android 13+)
    val needsNotificationPermission = remember {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }
    
    // State to track whether permission has been granted
    var permissionGranted by remember {
        mutableStateOf(
            !needsNotificationPermission || 
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == 
                PackageManager.PERMISSION_GRANTED
        )
    }
    
    // State to track if we need to show the permission rationale dialog
    var showRationaleDialog by remember { mutableStateOf(false) }
    
    // Permission launcher
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (!isGranted) {
            // Show rationale if permission was denied
            showRationaleDialog = true
        }
    }
    
    // Check if we need to request notification permission on first composition
    LaunchedEffect(needsNotificationPermission) {
        if (needsNotificationPermission && !permissionGranted) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    
    // Notification listener permission check
    val isNotificationListenerEnabled = remember {
        isNotificationServiceEnabled(context)
    }
    
    var showListenerDialog by remember { mutableStateOf(!isNotificationListenerEnabled) }
    
    // Permission rationale dialog
    if (showRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRationaleDialog = false },
            title = { Text("Notification Permission") },
            text = { 
                Text("To use Q25 LED notifications, the app needs permission to post notifications. " +
                     "Please grant this permission in the app settings.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Open app notification settings
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        }
                        showRationaleDialog = false
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                Button(onClick = { showRationaleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Notification listener service dialog
    if (showListenerDialog) {
        AlertDialog(
            onDismissRequest = { showListenerDialog = false },
            title = { Text("Notification Access") },
            text = {
                Text("For LED notifications, Q25 LED needs access to your notifications. " +
                     "Please enable Q25 LED in the Notification Access settings.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Open notification listener settings
                        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        context.startActivity(intent)
                        showListenerDialog = false
                    }
                ) {
                    Text("Open Settings")
                }
            },
            dismissButton = {
                Button(onClick = { showListenerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Render the content
    content()
}

/**
 * Checks if the notification listener service is enabled for this app
 */
private fun isNotificationServiceEnabled(context: Context): Boolean {
    val packageName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat?.contains(packageName) == true
}
