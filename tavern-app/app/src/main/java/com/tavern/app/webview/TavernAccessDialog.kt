package com.tavern.app.webview

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.ColorUtils
import com.tavern.app.R
import com.tavern.app.console.theme.AppAlpha
import com.tavern.app.console.theme.AppDimens
import com.tavern.app.console.theme.AppRadius
import com.tavern.app.console.theme.AppSpacing
import com.tavern.app.console.theme.FluentTypography
import com.tavern.app.console.theme.ViewPalette
import kotlin.math.roundToInt

/** One intercepted request, in the terms the user needs in order to decide. */
internal data class TavernAccessRequest(
    val sourceUrl: String?,
    val targetUrl: String,
    val method: String,
)

/**
 * The dialog that answers a request from a page outside the tavern.
 *
 * It is a platform dialog built in the View layer and cannot be a Compose dialog:
 * the tavern is shown by handing the window a native WebView, so no composition
 * runs underneath it. For the same reason the colours come from ViewPalette,
 * which resolves the app's current mode itself and then takes its values from the
 * same function the theme does.
 */
internal object TavernAccessDialog {

    // The adaptive foreground keeps its artwork inside a safe zone, so it is drawn
    // past the plate to fill it: the same oversize the console's brand mark uses.
    private const val BRAND_GLYPH_OVERSIZE = 1.35f
    private const val SEMIBOLD_WEIGHT = 600

    // The grant lasts for the run and covers reading as well as writing, so these
    // say what follows from allowing rather than what the one request asks for.
    private const val CONSEQUENCE_TITLE = "允许后可能发生什么"
    private val CONSEQUENCE_POINTS = listOf(
        "授权后，该页面可以读写本机的核心数据：聊天记录、角色卡、世界书与设置。",
        "该页面也可能把读到的内容发送到它自己的服务器，用途无法确认。",
        "仅在确认页面可信且确实需要时授予权限。",
        "权限仅在本次运行内有效，关闭应用后需要重新授权。",
    )

    fun show(activity: Activity, request: TavernAccessRequest, onAnswer: (Boolean) -> Unit) {
        val palette = ViewPalette.current(activity)
        var decision = false
        lateinit var dialog: AlertDialog

        val decide: (Boolean) -> Unit = { allowed ->
            decision = allowed
            dialog.dismiss()
        }

        dialog = AlertDialog.Builder(activity)
            .setView(
                ScrollView(activity).apply {
                    isFillViewport = true
                    addView(column(activity, palette, request, decide))
                }
            )
            .setCancelable(false)
            .create()

        // Every exit reports an answer: a dismissal that came from somewhere else
        // (the activity going away) must release the request waiting on it too.
        dialog.setOnDismissListener { onAnswer(decision) }

        // The platform plate follows the framework theme, which is not the app's
        // palette; replacing the background is what makes the dialog follow it.
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(palette.surface)
                cornerRadius = activity.px(AppRadius.lg).toFloat()
            }
        )
        dialog.show()
        // The platform background is gone, so its window insets are too: the dialog
        // takes the screen width less one gutter on each side.
        dialog.window?.setLayout(
            activity.resources.displayMetrics.widthPixels - activity.px(AppSpacing.lg) * 2,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun column(
        context: Context,
        palette: ViewPalette.Palette,
        request: TavernAccessRequest,
        decide: (Boolean) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            context.px(AppSpacing.xxl),
            context.px(AppSpacing.xxl),
            context.px(AppSpacing.xxl),
            context.px(AppSpacing.lg),
        )
        addView(header(context, palette))
        addSpaced(section(context, palette, "谁在请求", request.sourceUrl ?: "来源不明的页面"))
        addSpaced(section(context, palette, "请求内容", "${request.method} ${request.targetUrl}"))
        addSpaced(consequences(context, palette))
        addSpaced(buttons(context, palette, decide), AppSpacing.xl)
    }

    private fun header(context: Context, palette: ViewPalette.Palette): View {
        val plate = context.px(AppDimens.iconBadge)
        val plateColor = context.getColor(R.color.ic_launcher_background)
        val plateCorner = context.px(AppRadius.sm).toFloat()
        val glyphSize = (plate * BRAND_GLYPH_OVERSIZE).roundToInt()
        val nameGutter = context.px(AppSpacing.md)
        val mark = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(plateColor)
                cornerRadius = plateCorner
            }
            // The glyph is drawn larger than the plate to fill it, so the plate has
            // to clip it: an ImageView cannot draw the launcher's adaptive icon XML,
            // and plate plus foreground is how the app already renders its brand.
            clipToOutline = true
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_launcher_foreground)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(glyphSize, glyphSize, Gravity.CENTER)
                }
            )
        }
        val names = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = nameGutter }
            addView(styledText(context, "ST Ctrl", FluentTypography.titleLarge, palette.onSurface))
            addView(
                styledText(context, "本地核心访问请求", FluentTypography.bodyMedium, palette.onSurfaceVariant)
                    .apply { marginTop(context, AppSpacing.xxs) }
            )
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mark, LinearLayout.LayoutParams(plate, plate))
            addView(names)
        }
    }

    /**
     * What allowing gives away, as one block: the answer covers all of it, and the
     * page's use of what it reads is not something this app can observe.
     */
    private fun consequences(context: Context, palette: ViewPalette.Palette): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(styledText(context, CONSEQUENCE_TITLE, FluentTypography.titleSmall, palette.onSurface))
            CONSEQUENCE_POINTS.forEach { point ->
                addView(
                    wrappedText(context, point, FluentTypography.bodySmall, palette.onSurfaceVariant)
                        .apply { marginTop(context, AppSpacing.xs) }
                )
            }
        }

    private fun section(
        context: Context,
        palette: ViewPalette.Palette,
        title: String,
        body: String,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(styledText(context, title, FluentTypography.titleSmall, palette.onSurface))
        addView(
            wrappedText(context, body, FluentTypography.bodyLarge, palette.onSurfaceVariant)
                .apply { marginTop(context, AppSpacing.xs) }
        )
    }

    private fun buttons(
        context: Context,
        palette: ViewPalette.Palette,
        decide: (Boolean) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(button(context, "不允许", palette.onSurfaceVariant) { decide(false) })
        addView(button(context, "允许", palette.accent) { decide(true) })
    }

    private fun button(context: Context, label: String, color: Int, onClick: () -> Unit): TextView =
        styledText(context, label, FluentTypography.labelLarge, color).apply {
            isClickable = true
            isFocusable = true
            gravity = Gravity.CENTER
            minHeight = context.px(AppDimens.controlHeight)
            minWidth = context.px(AppDimens.controlHeight)
            setPadding(context.px(AppSpacing.md), 0, context.px(AppSpacing.md), 0)
            // A dialog action draws no container, so the ripple is its only feedback:
            // it has to come from the app's own colour rather than the framework theme.
            background = ripple(color)
            setOnClickListener { onClick() }
        }

    /** A request address carries no spaces, so the default strategy runs it off the line. */
    private fun wrappedText(context: Context, content: String, style: TextStyle, color: Int): TextView =
        styledText(context, content, style, color).apply {
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }

    private fun styledText(context: Context, content: String, style: TextStyle, color: Int): TextView =
        TextView(context).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.value)
            typeface = typefaceFor(style.fontWeight)
            setTextColor(color)
        }

    /** Typeface for a token weight; the named-weight lookup needs a newer API than this app's floor. */
    private fun typefaceFor(weight: FontWeight?): Typeface =
        if ((weight?.weight ?: 400) >= SEMIBOLD_WEIGHT) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    private fun ripple(color: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(color, (AppAlpha.subtle * 255).roundToInt())
        ),
        null,
        null,
    )

    private fun LinearLayout.addSpaced(child: View, topMargin: Dp = AppSpacing.lg) {
        val margin = context.px(topMargin)
        addView(
            child,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { this.topMargin = margin }
        )
    }

    private fun View.marginTop(context: Context, size: Dp) {
        val params = layoutParams as? LinearLayout.LayoutParams ?: return
        params.topMargin = context.px(size)
        layoutParams = params
    }

    private fun Context.px(size: Dp): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, size.value, resources.displayMetrics)
            .roundToInt()
}
