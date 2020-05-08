package tk.zwander.lockscreenwidgets.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.appwidget.AppWidgetManager
import android.content.*
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import kotlinx.android.synthetic.main.widget_frame.view.*
import tk.zwander.lockscreenwidgets.adapters.WidgetFrameAdapter
import tk.zwander.lockscreenwidgets.host.WidgetHost
import tk.zwander.lockscreenwidgets.interfaces.OnSnapPositionChangeListener
import tk.zwander.lockscreenwidgets.util.*
import tk.zwander.systemuituner.lockscreenwidgets.R
import kotlin.math.roundToInt
import kotlin.math.sign

class Accessibility : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val kgm by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    private val widgetManager by lazy { AppWidgetManager.getInstance(this) }
    private val widgetHost by lazy { WidgetHost(this, 1003) }

    private val view by lazy {
        LayoutInflater.from(ContextThemeWrapper(this, R.style.AppTheme))
            .inflate(R.layout.widget_frame, null)
    }

    private val adapter by lazy {
        WidgetFrameAdapter(widgetManager, widgetHost)
    }

    private val params by lazy {
        WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            width = dpAsPx(prefManager.frameWidthDp)
            height = dpAsPx(prefManager.frameHeightDp)

            x = prefManager.posX
            y = prefManager.posY

            gravity = Gravity.CENTER
            flags =
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.RGBA_8888
        }
    }

    private val pagerSnapHelper by lazy { PagerSnapHelper() }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    removeOverlay()
                }
                Intent.ACTION_SCREEN_ON -> {
                    if (isLocked()) {
                        addOverlay()
                    }
                }
            }
        }
    }

    private var updatedForMove = false

    private val touchHelperCallback by lazy {
        object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return adapter.onMove(viewHolder.adapterPosition, target.adapterPosition).also {
                    if (it) {
                        updatedForMove = true
                        prefManager.currentWidgets = adapter.widgets.toHashSet()
                    }
                }
            }

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                return if (viewHolder is WidgetFrameAdapter.AddWidgetVH) 0
                else super.getDragDirs(recyclerView, viewHolder)
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                if (actionState != ItemTouchHelper.ACTION_STATE_IDLE) {
                    viewHolder?.itemView?.alpha = 0.5f
                    pagerSnapHelper.attachToRecyclerView(null)
                }

                super.onSelectedChanged(viewHolder, actionState)
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)

                viewHolder.itemView.alpha = 1.0f
                pagerSnapHelper.attachToRecyclerView(view.widgets_pager)
            }

            override fun interpolateOutOfBoundsScroll(
                recyclerView: RecyclerView,
                viewSize: Int,
                viewSizeOutOfBounds: Int,
                totalSize: Int,
                msSinceStartScroll: Long
            ): Int {
                val direction = sign(viewSizeOutOfBounds.toFloat()).toInt()
                return (viewSize * 0.01f * direction).roundToInt()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        view.widgets_pager.apply {
            adapter = this@Accessibility.adapter
            setHasFixedSize(true)
            pagerSnapHelper.attachToRecyclerView(this)
            ItemTouchHelper(touchHelperCallback).attachToRecyclerView(this)
        }

        adapter.updateWidgets(prefManager.currentWidgets.toList())
        prefManager.prefs.registerOnSharedPreferenceChangeListener(this)
        widgetHost.startListening()

        view.frame.onMoveListener = { velX, velY ->
            params.x += velX.toInt()
            params.y += velY.toInt()

            prefManager.posX = params.x
            prefManager.posY = params.y

            updateOverlay()
        }
        view.frame.onResizeListener = { velX, velY ->
            params.width += velX.toInt()
            params.height += velY.toInt()

            params.x += (velX / 2f).toInt()
            params.y += (velY / 2f).toInt()

            prefManager.frameWidthDp = pxAsDp(params.width)
            prefManager.frameHeightDp = pxAsDp(params.height)

            updateOverlay()
        }
        view.frame.onInterceptListener = { down ->
            if (down) {
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            } else {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            }

            updateOverlay()
        }
        view.frame.onRemoveListener = {
            (view.widgets_pager.layoutManager as LinearLayoutManager).apply {
                val index = findFirstCompletelyVisibleItemPosition()
                val item = adapter.widgets[index]
                prefManager.currentWidgets = prefManager.currentWidgets.apply {
                    remove(item)
                }
            }
        }

        view.widgets_pager.addOnScrollListener(SnapScrollListener(pagerSnapHelper, object : OnSnapPositionChangeListener {
            override fun onSnapPositionChange(position: Int) {
                view.frame.shouldShowRemove = position < adapter.widgets.size
                view.remove.isVisible = view.frame.isInEditingMode && view.frame.shouldShowRemove
            }
        }))

        registerReceiver(screenStateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (isLocked()) {
                addOverlay()
            } else {
                removeOverlay()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PrefManager.KEY_CURRENT_WIDGETS -> {
                if (updatedForMove) {
                    updatedForMove = false
                } else {
                    adapter.updateWidgets(prefManager.currentWidgets.toList())
                }
            }
            PrefManager.KEY_FRAME_WIDTH, PrefManager.KEY_FRAME_HEIGHT -> {
//                params.width = dpAsPx(prefManager.frameWidthDp)
//                params.height = dpAsPx(prefManager.frameHeightDp)
//
//                updateOverlay()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        prefManager.prefs.unregisterOnSharedPreferenceChangeListener(this)
        widgetHost.stopListening()
        unregisterReceiver(screenStateReceiver)
    }

    private fun addOverlay() {
        try {
            wm.addView(view, params)
        } catch (e: Exception) {}
    }

    private fun updateOverlay() {
        try {
            wm.updateViewLayout(view, params)
        } catch (e: Exception) {}
    }

    private fun removeOverlay() {
        try {
            wm.removeView(view)
        } catch (e: Exception) {}
    }

    private fun isLocked() = kgm.isKeyguardLocked
}