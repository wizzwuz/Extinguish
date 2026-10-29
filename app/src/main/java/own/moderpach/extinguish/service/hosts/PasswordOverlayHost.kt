package own.moderpach.extinguish.service.hosts

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import own.moderpach.extinguish.R
import own.moderpach.extinguish.ui.theme.ExtinguishThemeForNonActivityContext

private const val TAG = "PasswordOverlayHost"

class PasswordOverlayHost(
    private val context: Context,
    var onUnlockSuccess: () -> Unit = {},
    var onGoToLockScreen: () -> Unit = {},
    var onCollapsePanels: () -> Unit = {}
) : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController =
        SavedStateRegistryController.create(this).apply {
            performRestore(null)
        }
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var collapseJob: Job? = null

    var isShowing = false
        private set

    private var targetPassword by mutableStateOf("")
    private var wrongAttempts by mutableIntStateOf(0)
    private var isLockedOut by mutableStateOf(false)

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        gravity = Gravity.TOP or Gravity.START
        width = WindowManager.LayoutParams.MATCH_PARENT
        height = WindowManager.LayoutParams.MATCH_PARENT
        flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
    }

    private inner class CustomOverlayView(context: Context) : ComposeView(context) {

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    Log.d(TAG, "Back pressed while password overlay showing -> taking to lock screen")
                    handleLockScreenTrigger()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
                Log.d(TAG, "Home or AppSwitch pressed while password overlay showing -> taking to lock screen")
                handleLockScreenTrigger()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            // Block status bar pull down
            if (ev.rawY < 150f) {
                onCollapsePanels()
                return true
            }
            // Detect bottom navigation gesture attempt (swipe up near bottom edge)
            val displayHeight = resources.displayMetrics.heightPixels
            if (ev.rawY > (displayHeight - 140)) {
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                    Log.d(TAG, "Bottom navigation gesture detected -> taking to lock screen")
                    handleLockScreenTrigger()
                    return true
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus && isShowing && !isLockedOut) {
                Log.d(TAG, "Window focus lost while password overlay showing -> taking to lock screen")
                onCollapsePanels()
                handleLockScreenTrigger()
            }
        }
    }

    private val customView = CustomOverlayView(context).apply {
        setViewTreeLifecycleOwner(this@PasswordOverlayHost)
        setViewTreeSavedStateRegistryOwner(this@PasswordOverlayHost)
        setContent {
            ExtinguishThemeForNonActivityContext(darkTheme = true) {
                PasswordOverlayContent()
            }
        }
    }

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun show(password: String) {
        if (isShowing) return
        targetPassword = password
        wrongAttempts = 0
        isLockedOut = false
        isShowing = true

        try {
            if (!customView.isAttachedToWindow) {
                windowManager.addView(customView, layoutParams)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay window", e)
        }

        onCollapsePanels()
        startPeriodicCollapse()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        stopPeriodicCollapse()

        try {
            if (customView.isAttachedToWindow) {
                windowManager.removeView(customView)
            }
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay window", e)
        }
    }

    fun destroy() {
        hide()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    private fun startPeriodicCollapse() {
        collapseJob?.cancel()
        collapseJob = coroutineScope.launch {
            while (isActive && isShowing) {
                onCollapsePanels()
                delay(300)
            }
        }
    }

    private fun stopPeriodicCollapse() {
        collapseJob?.cancel()
        collapseJob = null
    }

    private fun handleLockScreenTrigger() {
        if (!isShowing || isLockedOut) return
        isLockedOut = true
        hide()
        onGoToLockScreen()
    }

    @Composable
    private fun PasswordOverlayContent() {
        var enteredPin by remember { mutableStateOf("") }
        val maxAttempts = 5

        LaunchedEffect(wrongAttempts) {
            if (wrongAttempts >= maxAttempts) {
                isLockedOut = true
                delay(200)
                handleLockScreenTrigger()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0D0D))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {},
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.extinguish_24px),
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.str_device_locked),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (wrongAttempts > 0) {
                        stringResource(R.string.str_wrong_pin_attempts, maxAttempts - wrongAttempts)
                    } else {
                        stringResource(R.string.str_enter_pin_to_unlock)
                    },
                    fontSize = 14.sp,
                    color = if (wrongAttempts > 0) MaterialTheme.colorScheme.error else Color.Gray,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(28.dp))

                // PIN indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dotCount = targetPassword.length.coerceAtLeast(4)
                    for (i in 0 until dotCount) {
                        val isFilled = i < enteredPin.length
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isFilled) MaterialTheme.colorScheme.primary
                                    else Color(0xFF2C2C2C)
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(36.dp))

                // Keypad grid
                val rows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("C", "0", "DEL")
                )

                for (row in rows) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (key in row) {
                            KeypadButton(
                                text = key,
                                onClick = {
                                    when (key) {
                                        "C" -> enteredPin = ""
                                        "DEL" -> {
                                            if (enteredPin.isNotEmpty()) {
                                                enteredPin = enteredPin.dropLast(1)
                                            }
                                        }
                                        else -> {
                                            if (enteredPin.length < 16) {
                                                val nextPin = enteredPin + key
                                                enteredPin = nextPin

                                                if (nextPin == targetPassword) {
                                                    onUnlockSuccess()
                                                } else if (nextPin.length >= targetPassword.length) {
                                                    wrongAttempts++
                                                    enteredPin = ""
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun KeypadButton(
        text: String,
        onClick: () -> Unit
    ) {
        Surface(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .clickable { onClick() },
            shape = CircleShape,
            color = if (text == "C" || text == "DEL") Color(0xFF1E1E1E) else Color(0xFF242424),
            contentColor = Color.White
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (text == "DEL") {
                    Icon(
                        painter = painterResource(id = R.drawable.backspace_24px),
                        contentDescription = "Delete",
                        modifier = Modifier.size(24.dp),
                        tint = Color.White
                    )
                } else {
                    Text(
                        text = text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
