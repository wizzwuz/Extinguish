package own.moderpach.extinguish.service.hosts

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
    var onCollapsePanels: () -> Unit = {},
    var onShowOverlay: () -> Unit = {},
    var onHideOverlay: () -> Unit = {}
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
    private var statusBarBlockerView: View? = null

    var isShowing = false
        private set

    private var targetPassword by mutableStateOf("")
    private var wrongAttempts by mutableIntStateOf(0)
    private var isTriggeringLock = false
    private var hasGainedFocus = false

    private fun getRealScreenWidth(): Int {
        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return displayMetrics.widthPixels
    }

    private fun getRealScreenHeight(): Int {
        val displayMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        return displayMetrics.heightPixels
    }

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

    private fun addStatusBarBlocker() {
        if (statusBarBlockerView != null) return
        try {
            val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 100
            val blockerHeight = (statusBarHeight * 1.8).toInt().coerceAtLeast(160)

            val blockerParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = blockerHeight
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }

            val blocker = object : View(context) {
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    Log.d(TAG, "StatusBarBlocker touched -> collapsing panels")
                    onCollapsePanels()
                    return true
                }
            }
            windowManager.addView(blocker, blockerParams)
            statusBarBlockerView = blocker
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add status bar blocker", e)
        }
    }

    private fun removeStatusBarBlocker() {
        statusBarBlockerView?.let {
            try {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove status bar blocker", e)
            }
            statusBarBlockerView = null
        }
    }

    private inner class CustomOverlayContainer(context: Context) : android.widget.FrameLayout(context) {

        private var touchDownX = 0f
        private var touchDownY = 0f
        private var isEdgeGesture = false

        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            Log.d(TAG, "dispatchKeyEvent: keyCode=${event.keyCode}, action=${event.action}")
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "Back button pressed -> taking to lock screen")
                    handleLockScreenTrigger()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_HOME || event.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Log.d(TAG, "Home or AppSwitch button pressed -> taking to lock screen")
                    handleLockScreenTrigger()
                }
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            val realWidth = getRealScreenWidth()
            val realHeight = getRealScreenHeight()

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = ev.rawX
                    touchDownY = ev.rawY

                    // Top notification bar / quick settings shade (top 150px)
                    if (ev.rawY < 150f) {
                        onCollapsePanels()
                        return true
                    }

                    // Bottom navigation bar / gesture touch (bottom 140px)
                    if (ev.rawY > (realHeight - 140f)) {
                        Log.d(TAG, "Bottom nav touch detected -> taking to lock screen")
                        handleLockScreenTrigger()
                        return true
                    }

                    // Left edge (< 50px) or right edge (> width - 50px) back gesture
                    isEdgeGesture = (ev.rawX < 50f || ev.rawX > (realWidth - 50f))
                }

                MotionEvent.ACTION_MOVE -> {
                    // Block notification shade pulling
                    if (ev.rawY < 150f) {
                        onCollapsePanels()
                        return true
                    }

                    // Bottom gesture swipe up (Home / Recent)
                    if (ev.rawY > (realHeight - 140f)) {
                        Log.d(TAG, "Bottom nav swipe detected -> taking to lock screen")
                        handleLockScreenTrigger()
                        return true
                    }

                    // Back gesture swipe inward from left/right edge
                    if (isEdgeGesture) {
                        val deltaX = Math.abs(ev.rawX - touchDownX)
                        if (deltaX > 35f) {
                            Log.d(TAG, "Back gesture swipe detected -> taking to lock screen")
                            handleLockScreenTrigger()
                            return true
                        }
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (isEdgeGesture) {
                        val deltaX = Math.abs(ev.rawX - touchDownX)
                        if (deltaX > 25f) {
                            Log.d(TAG, "Back gesture swipe completed -> taking to lock screen")
                            handleLockScreenTrigger()
                            return true
                        }
                    }
                }
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            Log.d(TAG, "onWindowFocusChanged: hasWindowFocus=$hasWindowFocus, hasGainedFocus=$hasGainedFocus, isShowing=$isShowing")
            if (hasWindowFocus) {
                hasGainedFocus = true
            } else if (hasGainedFocus && isShowing && !isTriggeringLock) {
                Log.d(TAG, "Window lost focus while overlay showing -> taking to lock screen")
                onCollapsePanels()
                handleLockScreenTrigger()
            }
        }
    }

    private val customView = CustomOverlayContainer(context).apply {
        setViewTreeLifecycleOwner(this@PasswordOverlayHost)
        setViewTreeSavedStateRegistryOwner(this@PasswordOverlayHost)
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@PasswordOverlayHost)
            setViewTreeSavedStateRegistryOwner(this@PasswordOverlayHost)
            setContent {
                ExtinguishThemeForNonActivityContext(darkTheme = true) {
                    PasswordOverlayContent()
                }
            }
        }
        addView(
            composeView,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    init {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun show(password: String) {
        if (isShowing) return
        targetPassword = password
        wrongAttempts = 0
        isTriggeringLock = false
        hasGainedFocus = false
        isShowing = true

        try {
            if (!customView.isAttachedToWindow) {
                windowManager.addView(customView, layoutParams)
            }
            customView.post {
                customView.isFocusable = true
                customView.isFocusableInTouchMode = true
                customView.requestFocus()
            }
            addStatusBarBlocker()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay window", e)
        }

        onShowOverlay()
        onCollapsePanels()
        startPeriodicCollapse()
    }

    fun hide() {
        if (!isShowing) return
        isShowing = false
        stopPeriodicCollapse()
        removeStatusBarBlocker()
        onHideOverlay()

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
                delay(100)
            }
        }
    }

    private fun stopPeriodicCollapse() {
        collapseJob?.cancel()
        collapseJob = null
    }

    private fun handleLockScreenTrigger() {
        if (!isShowing || isTriggeringLock) return
        isTriggeringLock = true
        hide()
        onGoToLockScreen()
    }

    @Composable
    private fun PasswordOverlayContent() {
        var enteredPin by remember { mutableStateOf("") }
        val maxAttempts = 5

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

                val attemptsLeft = (maxAttempts - wrongAttempts).coerceAtLeast(0)
                Text(
                    text = if (wrongAttempts > 0) {
                        stringResource(R.string.str_wrong_pin_attempts, attemptsLeft)
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
                                    if (wrongAttempts >= maxAttempts) {
                                        handleLockScreenTrigger()
                                        return@KeypadButton
                                    }
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
                                                    val newWrongAttempts = wrongAttempts + 1
                                                    wrongAttempts = newWrongAttempts
                                                    enteredPin = ""
                                                    if (newWrongAttempts >= maxAttempts) {
                                                        Log.d(TAG, "0 attempts left reached -> taking to lock screen")
                                                        coroutineScope.launch {
                                                            delay(250)
                                                            handleLockScreenTrigger()
                                                        }
                                                    }
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
