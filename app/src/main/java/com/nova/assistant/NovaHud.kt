package com.nova.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
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
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure drawing logic for the "transformer" card. No Android window code here, so it is easy to reason about.
 * 12 steel plates fly in from random directions, spinning, and snap together into one card; the text appears
 * after they have joined. Reverse for hiding.
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
        if (v != null) { v.setState(state); return }
        val nv = HudView(ctx)
        nv.setState(state)
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
        lp.y = (72 * ctx.resources.displayMetrics.density).toInt()
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
    fun setState(state: String) { view?.setState(state) }

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
    private val cardW = (minOf(ctx.resources.displayMetrics.widthPixels - 40 * d, 340 * d)).toInt()
    private val cardH = (206 * d).toInt()

    var heard = ""
    var reply = ""
    var state = "listen"
    var onDismissTap: (() -> Unit)? = null
    @Volatile var level = 0f            // mic loudness 0..1 (written from the audio thread)
    private var smooth = 0f

    private var t = 0f                     // 0 = scattered, 1 = assembled
    private var anim: ValueAnimator? = null
    private var pulse = 0f

    private val rnd = Random(7L)
    private val fromX = FloatArray(HudMath.PANELS)
    private val fromY = FloatArray(HudMath.PANELS)
    private val fromRot = FloatArray(HudMath.PANELS)

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xFF27E1FF.toInt() }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = 0xFF27E1FF.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF27E1FF.toInt() }
    private val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF27E1FF.toInt(); textSize = 12f * d; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.15f
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8FA3B5.toInt(); textSize = 12f * d }
    private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEAF6FF.toInt(); textSize = 15f * d }
    private val rect = RectF()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val scan = Paint(Paint.ANTI_ALIAS_FLAG)
    private var plateShader: LinearGradient? = null
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; color = 0xFF2FE6FF.toInt() }
    private val inset = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF050A19.toInt() }
    private val strip = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND; color = 0xFF2FE6FF.toInt() }
    private val rivet = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB9D0F0.toInt() }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inRect = RectF()

    init {
        for (i in 0 until HudMath.PANELS) {
            val ang = rnd.nextFloat() * (2.0 * Math.PI).toFloat()
            val dist = (220f + rnd.nextFloat() * 260f) * d
            fromX[i] = Math.cos(ang.toDouble()).toFloat() * dist
            fromY[i] = Math.sin(ang.toDouble()).toFloat() * dist
            fromRot[i] = (rnd.nextFloat() - 0.5f) * 540f
        }
        setOnClickListener { onDismissTap?.invoke() }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(cardW, cardH)
    }

    fun setState(s: String) { state = s; invalidate() }

    fun assemble() { run(0f, 1f, 650L, null) }
    fun disassemble(end: () -> Unit) { run(t, 0f, 420L, end) }
    fun cancelAnim() { anim?.cancel(); anim = null }

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
                else startPulse()
            }
        })
        anim = a
        a.start()
    }

    private fun startPulse() {
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 1100L
        a.repeatCount = ValueAnimator.INFINITE
        a.repeatMode = ValueAnimator.REVERSE
        a.addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
        anim = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = cardW.toFloat()
        val h = cardH.toFloat()
        val gap = 2f * d
        val pw = w / HudMath.COLS
        val ph = h / HudMath.ROWS
        for (i in 0 until HudMath.PANELS) {
            val col = i % HudMath.COLS
            val row = i / HudMath.COLS
            val p = HudMath.plateProgress(i, t)
            val e = HudMath.easeOutBack(p)
            val cx = col * pw + pw / 2f
            val cy = row * ph + ph / 2f
            val k = 1f - e
            c.save()
            c.translate(cx + fromX[i] * k, cy + fromY[i] * k)
            c.rotate(fromRot[i] * k)
            val s = 0.35f + 0.65f * minOf(e, 1f)
            c.scale(s, s)
            val sh = plateShader ?: LinearGradient(
                -pw / 2f, -ph / 2f, pw / 2f, ph / 2f,
                intArrayOf(0xFF1A2640.toInt(), 0xFF6F86AD.toInt(), 0xFF0F1A2E.toInt(), 0xFF8FA6C9.toInt(), 0xFF16213A.toInt()),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP
            ).also { plateShader = it }
            val pa = minOf(1f, p * 2.5f)
            plate.shader = sh
            plate.alpha = (255 * pa).toInt()
            rect.set(-pw / 2f + gap, -ph / 2f + gap, pw / 2f - gap, ph / 2f - gap)
            c.drawRoundRect(rect, 5f * d, 5f * d, plate)
            // raised inner panel, light strip and rivets (the pod look)
            inRect.set(rect.left + 4f * d, rect.top + 4f * d, rect.right - 4f * d, rect.bottom - 4f * d)
            inset.alpha = (120 * pa).toInt()
            c.drawRoundRect(inRect, 3f * d, 3f * d, inset)
            strip.alpha = (255 * pa * (0.35f + 0.65f * pulse)).toInt()
            c.drawLine(inRect.left + 4f * d, inRect.top, inRect.right - 4f * d, inRect.top, strip)
            rivet.alpha = (230 * pa).toInt()
            val rr = 1.3f * d
            c.drawCircle(rect.left + 2.5f * d, rect.top + 2.5f * d, rr, rivet)
            c.drawCircle(rect.right - 2.5f * d, rect.top + 2.5f * d, rr, rivet)
            c.drawCircle(rect.left + 2.5f * d, rect.bottom - 2.5f * d, rr, rivet)
            c.drawCircle(rect.right - 2.5f * d, rect.bottom - 2.5f * d, rr, rivet)
            edge.alpha = (200 * pa).toInt()
            c.drawRoundRect(rect, 5f * d, 5f * d, edge)
            c.restore()
        }

        // blue seam flash through the middle as the last plates click together
        val fl = HudMath.seamFlare(t)
        if (fl > 0f) {
            val bx = w / 2f
            beam.shader = LinearGradient(
                bx - 16f * d, 0f, bx + 16f * d, 0f,
                intArrayOf(0x002FB8FF, 0xFFBFEFFF.toInt(), 0x002FB8FF), null, Shader.TileMode.CLAMP
            )
            beam.alpha = (255 * fl).toInt()
            c.drawRect(bx - 16f * d, 0f, bx + 16f * d, h, beam)
        }

        val ta = HudMath.textAlpha(t)
        if (ta <= 0f) return
        // glowing frame once joined
        glow.alpha = ((60 + 90 * pulse) * ta).toInt().coerceIn(0, 255)
        rect.set(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d)
        c.drawRoundRect(rect, 8f * d, 8f * d, glow)

        val pad = 16f * d
        val label = when (state) {
            "listen" -> "N O V A  //  LISTENING"
            "think" -> "N O V A  //  THINKING"
            else -> "N O V A"
        }
        // glowing core badge with two spinning arcs
        val ccx = pad + 10f * d
        val ccy = pad + 8f * d
        core.color = 0xFF04122B.toInt()
        core.alpha = (255 * ta).toInt()
        c.drawCircle(ccx, ccy, 7f * d, core)
        dot.alpha = (ta * (140 + 115 * pulse)).toInt().coerceIn(0, 255)
        c.drawCircle(ccx, ccy, 3f * d, dot)
        val spin = (SystemClock.uptimeMillis() % 2400L) / 2400f * 360f
        arc.alpha = (255 * ta).toInt()
        inRect.set(ccx - 10f * d, ccy - 10f * d, ccx + 10f * d, ccy + 10f * d)
        c.drawArc(inRect, spin, 100f, false, arc)
        c.drawArc(inRect, spin + 180f, 100f, false, arc)
        head.alpha = (255 * ta).toInt()
        c.drawText(label, pad + 26f * d, pad + 12f * d, head)

        var y = pad + 26f * d
        if (heard.isNotBlank()) {
            small.alpha = (255 * ta).toInt()
            c.drawText(TextUtils.ellipsize(heard, small, w - 2 * pad, TextUtils.TruncateAt.END).toString(), pad, y + 10f * d, small)
        }
        y += 22f * d
        val body = if (reply.isNotBlank()) reply else if (state == "listen") "Bolo, main sun raha hoon..." else "..."
        big.alpha = (255 * ta).toInt()
        val lay = build(body, big, (w - 2 * pad).toInt(), 4)
        c.save()
        c.translate(pad, y)
        lay.draw(c)
        c.restore()

        // ---- motion layer: ripples, mic waveform, thinking scanner (all drawn each frame by the pulse animator)
        val now = SystemClock.uptimeMillis()
        smooth += (level - smooth) * 0.3f
        val cx0 = pad + 10f * d
        val cy0 = pad + 8f * d
        if (state == "listen") {
            for (k in 0 until 3) {
                val p = ((now % 1800L) / 1800f + k / 3f) % 1f
                val ra = (200 * ta * HudMath.rippleAlpha(p)).toInt().coerceIn(0, 255)
                ring.color = 0x0027E1FF or (ra shl 24)
                c.drawCircle(cx0, cy0, (6f + (14f + 22f * smooth) * p) * d, ring)
            }
        }
        val n = HudMath.BARS
        val bw = (w - 2 * pad) / n
        val base = h - 14f * d
        for (i in 0 until n) {
            val v = HudMath.barHeight(i, n, smooth, now, state)
            val bh = 4f * d + v * 30f * d
            val col = HudMath.mix(0xFF2FE6FF.toInt(), 0xFF1B7CFF.toInt(), i / (n - 1f))
            bar.color = (col and 0x00FFFFFF) or (((ta * (140 + 115 * v)).toInt().coerceIn(0, 255)) shl 24)
            rect.set(pad + i * bw + bw * 0.2f, base - bh, pad + i * bw + bw * 0.8f, base)
            c.drawRoundRect(rect, bw * 0.3f, bw * 0.3f, bar)
        }
        if (state == "think") {                       // a soft light band sweeping over the whole card
            val sx = ((now % 1100L) / 1100f) * (w + 80f * d) - 40f * d
            scan.shader = LinearGradient(sx - 40f * d, 0f, sx + 40f * d, 0f,
                intArrayOf(0x0027E1FF, 0x4027E1FF, 0x0027E1FF), null, Shader.TileMode.CLAMP)
            rect.set(2f * d, 2f * d, w - 2f * d, h - 2f * d)
            c.drawRoundRect(rect, 8f * d, 8f * d, scan)
        }
    }

    private fun build(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout {
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(maxLines)
            .build()
    }
}
