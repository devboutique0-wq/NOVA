package com.nova.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure drawing maths for the NOVA card (no Android window code). The card is a dark glass panel with a glowing
 * orb; it fades and scales in. (The plate helpers of the old "transformer" card are kept only because they are tested.)
 */
internal object HudMath {
    const val COLS = 4
    const val ROWS = 3
    const val PANELS = COLS * ROWS

    /** Per-plate progress 0..1 for global time t (0..1). Plates start staggered, each takes 55% of the timeline. */
    fun plateProgress(index: Int, t: Float): Float {
        val delay = index * 0.035f
        val p = (t - delay) / 0.55f
        return if (p < 0f) 0f else if (p > 1f) 1f else p
    }

    /** easeOutBack: slight overshoot, which reads as the plate "clicking" into place. */
    fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = x - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    /** Pop-in curve for the glass card: t 0..1 -> 0..1, fast start, soft landing. */
    fun popEase(t: Float): Float {
        val k = t.coerceIn(0f, 1f)
        val u = 1f - k
        return 1f - u * u * u
    }

    /** Spring open: 0 -> about 1.04 -> 1 (a damped overshoot), so the card "snaps" in with weight. */
    fun springEase(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 1f - exp(-6f * x) * cos(10f * x)
    }

    /** Light burst while the card opens: 1 at the start, 0 when fully open (and always 0 outside 0..1). */
    fun entryFlash(t: Float): Float {
        if (t <= 0f || t >= 1f) return 0f
        val u = 1f - t
        return u * u
    }

    /** Brightness 0..1 of the glowing border: a slow breathing, a little stronger while listening. */
    fun glowLevel(nowMs: Long, state: String): Float {
        val base = if (state == "listen") 0.65f else 0.5f
        return (base + 0.25f * sin(nowMs / 450f)).coerceIn(0f, 1f)
    }

    const val BARS = 28

    /**
     * Height 0..1 of waveform bar [i] of [n]. listen = moves with the mic [level] (0..1), think = a scanner sweeps
     * across, anything else = a calm idle wave. [nowMs] is a monotonic clock.
     */
    fun barHeight(i: Int, n: Int, level: Float, nowMs: Long, state: String): Float {
        val x = i.toFloat() / n
        val tt = nowMs / 1000f
        val v = when (state) {
            "think" -> {
                val p = (tt * 0.9f) % 1f
                max(0f, 1f - abs(x - p) * 6f) * 0.9f + 0.08f
            }
            "listen" -> {
                val wave = 0.5f + 0.5f * sin(tt * 7f + i * 0.6f)
                0.12f + level.coerceIn(0f, 1f) * 0.88f * wave
            }
            else -> 0.1f + 0.12f * (0.5f + 0.5f * sin(tt * 2.5f + i * 0.4f))
        }
        return v.coerceIn(0f, 1f)
    }

    /** One expanding ring: [p] 0..1 -> alpha 1..0 (fades out as it grows). */
    fun rippleAlpha(p: Float): Float {
        val k = 1f - p.coerceIn(0f, 1f)
        return k * k
    }

    /** Linear colour mix (ARGB ints), t 0..1. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 0xff) + ((((b shr s) and 0xff) - ((a shr s) and 0xff)) * k)).toInt() and 0xff
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** 0..1..0 flash of the centre seam just as the plates finish joining (t 0.7 to 1.0). */
    fun seamFlare(t: Float): Float {
        val x = (t - 0.7f) / 0.3f
        return if (x <= 0f || x >= 1f) 0f else sin(Math.PI.toFloat() * x)
    }

    /** How long the card stays after a reply: long enough to READ it (replies are silent by default). */
    fun holdMs(replyLen: Int): Long = (1500L + replyLen * 55L).coerceIn(1500L, 9000L)

    /** Text becomes visible only after the plates joined (t from 0.75 to 1.0). */
    fun textAlpha(t: Float): Float {
        val a = (t - 0.75f) / 0.25f
        return if (a < 0f) 0f else if (a > 1f) 1f else a
    }
}

/**
 * Floating NOVA card shown over every app (needs the "Display over other apps" permission).
 * All methods must be called on the main thread. If the permission is missing everything is a silent no-op,
 * so voice commands keep working exactly as before.
 */
class NovaHud(private val ctx: Context) {

    private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    @Volatile private var view: HudView? = null
    private var shownAt = 0L
    private var lastReply = ""

    companion object {
        fun canShow(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
        const val MIN_VISIBLE_MS = 2500L
    }

    val isShown: Boolean get() = view != null
    fun visibleForMs(): Long = if (view == null) 0L else System.currentTimeMillis() - shownAt

    /** state: "listen", "think", "reply". */
    fun show(state: String) {
        if (!canShow(ctx)) return
        val v = view
        if (v != null) { v.changeState(state); return }
        val nv = HudView(ctx)
        nv.changeState(state)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = (44 * ctx.resources.displayMetrics.density).toInt()
        try {
            wm.addView(nv, lp)
            view = nv
            shownAt = System.currentTimeMillis()
            lastReply = ""
            nv.onDismissTap = { hide() }
            nv.assemble()
        } catch (e: Exception) {
            view = null     // no overlay allowed right now: keep running without the card
        }
    }

    /** Mic loudness 0..1, may be called from the audio thread (it only stores a number; the card redraws itself). */
    fun setLevel(l: Float) { view?.level = l }
    fun holdMs(): Long = HudMath.holdMs(view?.reply?.length ?: 0)

    fun setHeard(text: String) { view?.heard = text; view?.invalidate() }
    fun setReply(text: String) {
        view?.reply = text
        view?.invalidate()
        // one short tick when a reply appears (never while the mic is capturing a command, so it cannot be heard as speech)
        if (view != null && text.isNotBlank() && text != lastReply) tick()
        lastReply = text
    }

    @Suppress("DEPRECATION")
    private fun tick() {
        try {
            val v: android.os.Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (v == null) return
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createOneShot(22L, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                v.vibrate(22L)
            }
        } catch (e: Throwable) {
        }
    }
    fun setState(state: String) { view?.changeState(state) }

    /** Plates fly apart, then the window is removed. */
    fun hide() {
        val v = view ?: return
        view = null
        v.disassemble { try { wm.removeView(v) } catch (e: Exception) { } }
    }

    /** Immediate removal (service stopping). */
    fun destroy() {
        val v = view ?: return
        view = null
        v.cancelAnim()
        try { wm.removeView(v) } catch (e: Exception) { }
    }
}

private class HudView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val margin = 10f * d                      // room around the card for the glow
    private val cardW = (minOf(ctx.resources.displayMetrics.widthPixels - 24 * d, 380 * d)).toInt()
    private val cardH = (190f * d).toInt()
    private val viewW = cardW + (2 * margin).toInt()
    private val viewH = cardH + (2 * margin).toInt()

    var heard = ""
    var reply = ""
    var state = "listen"
    var onDismissTap: (() -> Unit)? = null
    @Volatile var level = 0f            // mic loudness 0..1 (written from the audio thread)
    private var smooth = 0f

    private var t = 0f                     // 0 = hidden, 1 = fully shown
    private var entering = false           // true only while the card opens (light burst is not played when closing)
    private var anim: ValueAnimator? = null
    private var ticker: ValueAnimator? = null

    private val cyan = 0xFF27E1FF.toInt()
    private val blue = 0xFF2F7BFF.toInt()
    private val violet = 0xFFA35CFF.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.4f * d; strokeCap = Paint.Cap.ROUND }
    private val orbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 18f * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9FB4D6.toInt(); textSize = 12.5f * d }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEAF2FF.toInt(); textSize = 14.5f * d }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6F86AD.toInt(); textSize = 13f * d }
    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shock = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val oval = RectF()
    private var fillShader: LinearGradient? = null
    private var borderShader: LinearGradient? = null
    private var barShader: LinearGradient? = null

    init {
        setOnClickListener { onDismissTap?.invoke() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(viewW, viewH)
    }

    fun changeState(s: String) { state = s; invalidate() }

    fun assemble() { entering = true; run(0f, 1f, 560L, null) }
    fun disassemble(end: () -> Unit) { entering = false; run(t, 0f, 240L, end) }
    fun cancelAnim() {
        anim?.cancel(); anim = null
        ticker?.cancel(); ticker = null
    }

    private fun run(from: Float, to: Float, ms: Long, end: (() -> Unit)?) {
        anim?.cancel()
        val a = ValueAnimator.ofFloat(from, to)
        a.duration = ms
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { t = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (cancelled) return          // a newer animation replaced this one
                if (end != null) end()
                else startTicker()
            }
        })
        anim = a
        a.start()
    }

    /** Keeps the orb, waveform and glow moving while the card is shown. */
    private fun startTicker() {
        ticker?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1000L
        a.repeatCount = ValueAnimator.INFINITE
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { invalidate() }
        ticker = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        ticker?.cancel(); ticker = null
        super.onDetachedFromWindow()
    }

    private fun stateColor(): Int = when (state) {
        "think" -> violet
        "listen" -> cyan
        else -> blue
    }

    override fun onDraw(c: Canvas) {
        val e = HudMath.popEase(t)
        if (e <= 0f) return
        val now = SystemClock.uptimeMillis()
        smooth += (level - smooth) * 0.3f
        val w = viewW.toFloat()
        val h = viewH.toFloat()
        val left = margin
        val top = margin
        val right = w - margin
        val bottom = h - margin
        val radius = 24f * d

        val sc = 0.78f + 0.22f * HudMath.springEase(t)
        val sv = c.save()
        c.translate(0f, -(1f - e) * 30f * d)       // slides down from above while it springs open
        c.scale(sc, sc, w / 2f, h / 2f)
        val layer = c.saveLayerAlpha(0f, 0f, w, h, (255 * e).toInt().coerceIn(0, 255))

        // glass body
        val fs = fillShader ?: LinearGradient(0f, top, 0f, bottom,
            intArrayOf(0xF2101C44.toInt(), 0xF20A1030.toInt(), 0xF2160C38.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        ).also { fillShader = it }
        fill.shader = fs
        rect.set(left, top, right, bottom)
        c.drawRoundRect(rect, radius, radius, fill)

        // glowing gradient border: three soft outer strokes + one crisp line
        val gl = HudMath.glowLevel(now, state)
        val bs = borderShader ?: LinearGradient(left, top, right, bottom,
            intArrayOf(cyan, blue, violet), null, Shader.TileMode.CLAMP
        ).also { borderShader = it }
        border.shader = bs
        for (k in 3 downTo 1) {
            border.strokeWidth = (1.5f + k * 2.6f) * d
            border.alpha = (28 * gl * (4 - k)).toInt().coerceIn(0, 255)
            c.drawRoundRect(rect, radius, radius, border)
        }
        border.strokeWidth = 1.5f * d
        border.alpha = (150 + 100 * gl).toInt().coerceIn(0, 255)
        c.drawRoundRect(rect, radius, radius, border)

        // orb
        val pad = 16f * d
        val r = 22f * d
        val ocx = left + pad + r + 4f * d
        val ocy = top + pad + r + 2f * d
        val sColor = stateColor()
        halo.shader = RadialGradient(ocx, ocy, r * 2.1f, intArrayOf((sColor and 0x00FFFFFF) or 0x66000000, (sColor and 0x00FFFFFF)), null, Shader.TileMode.CLAMP)
        c.drawCircle(ocx, ocy, r * 2.1f, halo)
        if (state == "listen") {
            for (k in 0 until 3) {
                val p = ((now % 1800L) / 1800f + k / 3f) % 1f
                val ra = (190 * HudMath.rippleAlpha(p)).toInt().coerceIn(0, 255)
                ring.color = (sColor and 0x00FFFFFF) or (ra shl 24)
                c.drawCircle(ocx, ocy, r * (0.9f + (0.9f + 0.9f * smooth) * p), ring)
            }
        }
        orbFill.shader = RadialGradient(ocx - r * 0.25f, ocy - r * 0.3f, r,
            intArrayOf(0xFF1B2C6B.toInt(), 0xFF070D27.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(ocx, ocy, r * 0.82f, orbFill)
        val spin = (now % (if (state == "think") 900L else 2600L)) / (if (state == "think") 900f else 2600f) * 360f
        oval.set(ocx - r, ocy - r, ocx + r, ocy + r)
        arc.color = cyan
        c.drawArc(oval, spin, 110f, false, arc)
        arc.color = violet
        c.drawArc(oval, -spin * 0.7f + 180f, 110f, false, arc)
        if (state == "think") {
            for (k in 0 until 3) {
                val a = Math.toRadians((spin * 1.3f + k * 120f).toDouble())
                dot.color = HudMath.mix(cyan, violet, k / 2f)
                c.drawCircle(ocx + (Math.cos(a) * r * 0.42f).toFloat(), ocy + (Math.sin(a) * r * 0.42f).toFloat(), 2.6f * d, dot)
            }
        } else {
            for (k in 0 until 5) {
                val v = HudMath.barHeight(k * 5, HudMath.BARS, smooth, now, state)
                val bh = (3f + v * 15f) * d
                bar.shader = null
                bar.color = HudMath.mix(cyan, violet, k / 4f)
                val bx = ocx + (k - 2) * 5.2f * d
                rect.set(bx - 1.5f * d, ocy - bh / 2f, bx + 1.5f * d, ocy + bh / 2f)
                c.drawRoundRect(rect, 1.5f * d, 1.5f * d, bar)
            }
        }

        // heading + live text
        val tx = ocx + r + 18f * d
        val textW = right - pad - tx
        val head = when (state) {
            "listen" -> "Listening\u2026"
            "think" -> "Thinking\u2026"
            else -> "NOVA"
        }
        c.drawText(head, tx, ocy - 2f * d, title)
        val line = heard.removePrefix("\uD83C\uDFA4").trim().ifBlank {
            when (state) { "listen" -> "I'm listening. You can speak now."; "think" -> "Working on your request"; else -> "More than an assistant" }
        }
        c.drawText(TextUtils.ellipsize(line, sub, textW, TextUtils.TruncateAt.END).toString(), tx, ocy + 18f * d, sub)

        // waveform
        val n = HudMath.BARS
        val bx0 = left + pad
        val bw = (right - pad - bx0) / n
        val base = top + pad + 96f * d
        val bs2 = barShader ?: LinearGradient(bx0, 0f, right - pad, 0f, intArrayOf(cyan, blue, violet), null, Shader.TileMode.CLAMP).also { barShader = it }
        bar.shader = bs2
        for (i in 0 until n) {
            val v = HudMath.barHeight(i, n, smooth, now, state)
            val bh = 3f * d + v * 28f * d
            bar.alpha = (120 + 135 * v).toInt().coerceIn(0, 255)
            rect.set(bx0 + i * bw + bw * 0.22f, base - bh, bx0 + i * bw + bw * 0.78f, base)
            c.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, bar)
        }
        bar.shader = null

        // reply (or a hint while nothing was said yet)
        val by = base + 12f * d
        val bodyW = (right - pad - bx0).toInt()
        if (reply.isNotBlank()) {
            val lay = build(reply, body, bodyW, 3)
            c.save(); c.translate(bx0, by); lay.draw(c); c.restore()
        } else if (state == "listen") {
            val lay = build("\u201Copen YouTube\u201D  \u00B7  \u201Ctorch on\u201D  \u00B7  \u201Cvolume badhao\u201D", hint, bodyW, 2)
            c.save(); c.translate(bx0, by); lay.draw(c); c.restore()
        }
        // opening light: border flash + a light band sweeping across the glass + one shock ring (only while opening)
        if (entering && t < 1f) {
            val fl = HudMath.entryFlash(t)
            rect.set(left, top, right, bottom)
            border.strokeWidth = 3.2f * d
            border.alpha = (255 * fl).toInt().coerceIn(0, 255)
            c.drawRoundRect(rect, radius, radius, border)
            val sx = left + (right - left) * (-0.25f + 1.5f * t)
            sweep.shader = LinearGradient(sx - 70f * d, top, sx + 70f * d, bottom,
                intArrayOf(0x00FFFFFF, 0x66CFF4FF, 0x00FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, radius, radius, sweep)
            sweep.shader = null
            val g = margin * 0.9f * t
            rect.set(left - g, top - g, right + g, bottom + g)
            shock.color = (cyan and 0x00FFFFFF) or ((190 * (1f - t)).toInt().coerceIn(0, 255) shl 24)
            shock.strokeWidth = 1.6f * d
            c.drawRoundRect(rect, radius + g, radius + g, shock)
        }
        c.restoreToCount(layer)
        c.restoreToCount(sv)
    }

    private fun build(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()
    }
}
