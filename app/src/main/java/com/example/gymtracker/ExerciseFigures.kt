package com.example.gymtracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

/*
 * Drawn exercise figures: one figure in the app's colours, posed at the start and end of each
 * exercise and moved between the two (or round a loop, for cardio). Poses are in figure units with
 * y pointing up and the floor at 0 (a standing figure is about 87 tall); angles are degrees from
 * straight up, clockwise, so 90 points forward (right) and 180 points down. Most figures are seen
 * from the side, facing right; [Figure.front] ones face the viewer, for side-to-side movements.
 * The poses themselves are in FigurePoses.kt.
 */

private const val TORSO = 27f
private const val NECK = 9.5f
private const val HEAD = 6.4f
private const val UPPER_ARM = 15f
private const val FOREARM = 14f
private const val THIGH = 21f
private const val SHIN = 20f
private const val PLATE = 11f

/** Where the far-side hand or foot sits, seen from the side, when a pose doesn't place it: just behind the near one. */
private val FAR = Offset(-2.5f, 0f)

/**
 * A hand or foot position: in the room (it stays put, like hands on the floor), or [onBody]:
 * relative to the shoulder or hip, so it moves with the body and swings in an arc around it.
 * [way] forces an arc's direction (1 = counter-clockwise) when the short way round is wrong.
 * With [crank] > 0 it goes round a circle of that size about (x, y) once per loop, from [phase].
 */
internal data class Target(
    val x: Float, val y: Float, val onBody: Boolean = false, val way: Int = 0,
    val crank: Float = 0f, val phase: Float = 0f, val straight: Boolean = false
)

internal fun room(x: Float, y: Float) = Target(x, y)
internal fun onBody(x: Float, y: Float, way: Int = 0) = Target(x, y, onBody = true, way = way)

/** Like [onBody], but reached in a straight line rather than an arc: a bar kept close to the body (cleans, upright rows). */
internal fun close(x: Float, y: Float) = Target(x, y, onBody = true, straight = true)
internal fun crank(x: Float, y: Float, r: Float, phase: Float = 0f, spin: Int = 1, onBody: Boolean = false) =
    Target(x, y, onBody, spin, r, phase)

internal val STAND = Offset(0f, 43.5f)
internal val HANG = onBody(1.5f, -28.5f)
internal val FEET = room(1f, 3f)

/**
 * One moment of an exercise. [elbow] / [knee] say which way those joints bend (a direction the
 * joint should point); [reach] / [legReach] shorten limbs pointing at the viewer; [squash]
 * shortens the torso (shrugs, or a bent-over body seen from the front); [thigh] shortens only the
 * thighs (seated, seen from the front).
 */
internal data class Pose(
    val hip: Offset = STAND,
    val torso: Float = 0f,
    val head: Float = 0f,
    val hand: Target = HANG,
    val foot: Target = FEET,
    val farHand: Target? = null,
    val farFoot: Target? = null,
    val toe: Float = 90f,
    val farToe: Float? = null,
    val elbow: Offset? = null,
    val farElbow: Offset? = null,
    val knee: Offset? = null,
    val farKnee: Offset? = null,
    val reach: Float = 1f,
    val farReach: Float? = null,
    val legReach: Float = 1f,
    val thigh: Float = 1f,
    val squash: Float = 1f
)

internal enum class Held { Barbell, EzBar, Dumbbell, Kettlebell, Plate, Ball, Handle }

internal enum class Role { Frame, Gear, Cable }

/** Equipment: a rounded line, a dot (a == b), a [ring] or a [box] between two corners; [front] ones are drawn over the body. */
internal class Part(
    val a: Offset, val b: Offset, val width: Float, val role: Role = Role.Frame,
    val front: Boolean = false, val ring: Boolean = false, val box: Boolean = false
)

internal fun strut(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 2.2f, role: Role = Role.Frame, front: Boolean = false) =
    Part(Offset(x1, y1), Offset(x2, y2), width, role, front)
internal fun strut(a: Offset, b: Offset, width: Float = 2.2f, role: Role = Role.Frame, front: Boolean = false) =
    Part(a, b, width, role, front)
internal fun pad(x1: Float, y1: Float, x2: Float, y2: Float, front: Boolean = false) = strut(x1, y1, x2, y2, 4.5f, front = front)
internal fun pad(a: Offset, b: Offset, front: Boolean = false) = strut(a, b, 4.5f, front = front)
internal fun knob(c: Offset, r: Float, role: Role = Role.Gear, front: Boolean = false) = Part(c, c, r * 2, role, front)
internal fun block(x1: Float, y1: Float, x2: Float, y2: Float) = Part(Offset(x1, y1), Offset(x2, y2), 0f, box = true)
internal fun wheel(c: Offset, r: Float, width: Float = 2f, role: Role = Role.Frame) = Part(c, c + Offset(r, 0f), width, role, ring = true)

/** A flat bench with its top at [top]. */
internal fun bench(left: Float, right: Float, top: Float = 16f): List<Part> = listOf(
    pad(left + 2.25f, top - 2.25f, right - 2.25f, top - 2.25f),
    strut(left + 6f, top - 4f, left + 6f, 0.8f),
    strut(right - 6f, top - 4f, right - 6f, 0.8f)
)

/**
 * An exercise's movement from [start] to [end]. [thumb] is the moment shown in lists. With
 * [loop] (ms) it plays as a continuous cycle: start, end and back, with cranks going round.
 * [cable] is a pulley the hands pull on; [parts] draws the rest of the equipment for each moment.
 * [turn] rotates the whole picture (degrees, counter-clockwise), for front views of lying moves.
 */
internal class Figure(
    val start: Pose,
    val end: Pose = start,
    val held: Held? = null,
    val oneHand: Boolean = false,
    val thumb: Float = 0f,
    val elbow: Offset = Offset(-0.4f, -1f),
    val knee: Offset = Offset(1f, 0.3f),
    val front: Boolean = false,
    val turn: Float = 0f,
    val cable: Offset? = null,
    val floor: Boolean = true,
    val loop: Int = 0,
    /** A pose half way, for movements that don't go straight from start to end (a muscle-up, a clean). */
    val mid: Pose? = null,
    val parts: (Body) -> List<Part> = { emptyList() }
)

internal class Limb(val root: Offset, val joint: Offset, val end: Offset)

internal class Body(
    val hip: Offset, val shoulder: Offset, val head: Offset, val up: Offset,
    val arm: Limb, val farArm: Limb, val leg: Limb, val farLeg: Limb,
    val toe: Float, val farToe: Float,
    /** How far along the movement (0–1) and round the loop, for equipment that moves on its own. */
    val t: Float, val time: Float
)

// --- Posing ---

internal fun dir(degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    return Offset(sin(r).toFloat(), cos(r).toFloat())
}

/** The angle (in pose terms) pointing from [from] to [to]. */
internal fun aim(from: Offset, to: Offset): Float =
    Math.toDegrees(atan2((to.x - from.x).toDouble(), (to.y - from.y).toDouble())).toFloat()

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun mix(a: Target, b: Target, t: Float): Target {
    if (a.crank > 0f) return a
    if (!a.onBody) return Target(mix(a.x, b.x, t), mix(a.y, b.y, t))
    if (a.straight || b.straight) return Target(mix(a.x, b.x, t), mix(a.y, b.y, t), onBody = true)
    val from = atan2(a.y, a.x)
    var to = atan2(b.y, b.x)
    val turn = 2 * PI.toFloat()
    when {
        a.way > 0 -> while (to < from) to += turn
        a.way < 0 -> while (to > from) to -= turn
        else -> {
            if (to - from > PI) to -= turn
            if (to - from < -PI) to += turn
        }
    }
    val angle = mix(from, to, t)
    val r = mix(hypot(a.x, a.y), hypot(b.x, b.y), t)
    return Target(r * cos(angle), r * sin(angle), onBody = true)
}

private fun dot(a: Offset, b: Offset) = a.x * b.x + a.y * b.y

/**
 * Two-bone reach from [root] towards [target], the middle joint towards [bend].
 *
 * The joint can sit on either side of the line to the target. Picking one outright made elbows
 * and knees jump across whenever the hand or foot passed the bend direction (an upright row, a
 * clean, a fly). Instead it moves across gradually: the more the bend lines up with the limb, the
 * nearer the line the joint sits, the way an elbow looks while it turns towards or away from the
 * viewer (the hand falls a little short then, as if the arm were foreshortened).
 */
private fun reach(root: Offset, target: Offset, upper: Float, lower: Float, bend: Offset): Limb {
    val v = target - root
    val d = v.getDistance().coerceIn(abs(upper - lower) + 0.01f, upper + lower - 0.01f)
    val base = atan2(v.y, v.x)
    val spread = acos(((upper * upper + d * d - lower * lower) / (2 * upper * d)).coerceIn(-1f, 1f))
    // How far the bend points to the side the joint turns towards (+spread), not along the limb.
    val across = v.getDistance().takeIf { it > 0.001f }?.let { len -> (v.x * bend.y - v.y * bend.x) / len } ?: 1f
    val side = (across / BEND_EASE).coerceIn(-1f, 1f)
    val joint = root + Offset(cos(base + spread * side), sin(base + spread * side)) * upper
    val rest = target - joint
    val len = rest.getDistance()
    return Limb(root, joint, if (len < 0.001f) joint else joint + rest / len * minOf(lower, len))
}

/** How far off the limb's line (as a sine) the bend must point for the joint to sit fully to one side. */
private const val BEND_EASE = 0.35f

/** The body at [time] (0–1): along the movement, or round the loop for [Figure.loop] ones. */
internal fun Figure.at(time: Float): Body {
    val along = if (loop > 0) (1 - cos(2 * PI * time).toFloat()) / 2 else time
    val a = if (mid != null && along > 0.5f) mid else start
    val b = if (mid != null && along <= 0.5f) mid else end
    val t = if (mid == null) along else if (along <= 0.5f) along * 2f else along * 2f - 1f
    val hip = lerp(a.hip, b.hip, t)
    val torso = mix(a.torso, b.torso, t)
    val up = dir(torso)
    val squash = mix(a.squash, b.squash, t)
    val center = hip + up * (TORSO * squash)
    // Shoulders raised past normal (a shrug) come up towards the ears: the head stays put.
    val neck = if (squash > 1f) NECK - TORSO * (squash - 1f) else NECK * squash
    val head = center + dir(torso + mix(a.head, b.head, t)) * neck
    // Seen from the front the shoulders and hips are apart, and the far side mirrors the near one.
    val side = if (front) Offset(up.y, -up.x) else Offset.Zero
    val shoulder = center + side * 8f
    val farShoulder = center - side * 8f
    val nearHip = hip + side * 4.5f
    val farHip = hip - side * 4.5f
    fun mirror(v: Offset) = up * (2 * dot(v, up)) - v
    fun place(target: Target, root: Offset): Offset {
        val base = if (target.onBody) root + Offset(target.x, target.y) else Offset(target.x, target.y)
        return if (target.crank > 0f) base + dir(target.phase + 360f * time * target.way) * target.crank else base
    }
    fun far(near: Target, far: Target?, otherFar: Target?, root: Offset, farRoot: Offset, pivot: Offset): Offset = when {
        far != null -> place(if (otherFar != null) mix(far, otherFar, t) else far, farRoot)
        !front -> place(near, root) + FAR
        near.onBody && near.crank == 0f -> farRoot + mirror(Offset(near.x, near.y))
        else -> pivot + mirror(place(near, root) - pivot)
    }
    fun bend(x: Offset?, y: Offset?, fallback: Offset) = lerp(x ?: fallback, y ?: fallback, t)
    val handT = mix(a.hand, b.hand, t)
    val footT = mix(a.foot, b.foot, t)
    val elbowBend = bend(a.elbow, b.elbow, elbow)
    val kneeBend = bend(a.knee, b.knee, knee)
    val farElbowBend = bend(a.farElbow, b.farElbow, if (front) mirror(elbowBend) else elbowBend)
    val farKneeBend = bend(a.farKnee, b.farKnee, if (front) mirror(kneeBend) else kneeBend)
    val armScale = mix(a.reach, b.reach, t)
    val farArmScale = mix(a.farReach ?: a.reach, b.farReach ?: b.reach, t)
    val legScale = mix(a.legReach, b.legReach, t)
    val thighScale = legScale * mix(a.thigh, b.thigh, t)
    val toe = mix(a.toe, b.toe, t)
    val farToe = when {
        a.farToe != null -> mix(a.farToe, b.farToe ?: a.farToe, t)
        front -> 2 * torso - toe
        else -> toe
    }
    return Body(
        hip, center, head, up,
        reach(shoulder, place(handT, shoulder), UPPER_ARM * armScale, FOREARM * armScale, elbowBend),
        reach(farShoulder, far(handT, a.farHand, b.farHand, shoulder, farShoulder, center), UPPER_ARM * farArmScale, FOREARM * farArmScale, farElbowBend),
        reach(nearHip, place(footT, nearHip), THIGH * thighScale, SHIN * legScale, kneeBend),
        reach(farHip, far(footT, a.farFoot, b.farFoot, nearHip, farHip, hip), THIGH * thighScale, SHIN * legScale, farKneeBend),
        toe, farToe, along, time
    )
}

private fun Offset.turned(degrees: Float): Offset {
    if (degrees == 0f) return this
    val r = Math.toRadians(degrees.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Offset(x * c - y * s, x * s + y * c)
}

private class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/** The space the whole movement takes, so the figure stays still in its frame while it moves. */
private fun Figure.bounds(): Bounds {
    var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    fun add(p: Offset, r: Float) {
        val q = p.turned(turn)
        minX = min(minX, q.x - r); maxX = max(maxX, q.x + r)
        minY = min(minY, q.y - r); maxY = max(maxY, q.y + r)
    }
    for (i in 0..12) {
        val b = at(i / 12f)
        add(b.hip, 7f); add(b.shoulder, 7f); add(b.head, HEAD + 1f)
        listOf(b.arm, b.farArm, b.leg, b.farLeg).forEach { add(it.joint, 5f); add(it.end, 4f) }
        add(b.leg.end + dir(b.toe) * 6f, 2.5f); add(b.farLeg.end + dir(b.farToe) * 6f, 2.5f)
        when (held) {
            Held.Barbell, Held.EzBar -> if (front) {
                val mid = lerp(b.arm.end, b.farArm.end, 0.5f)
                add(mid + Offset(31f, 0f), PLATE); add(mid - Offset(31f, 0f), PLATE)
            } else add(b.arm.end, (if (held == Held.Barbell) PLATE else PLATE * 0.8f) + 1.5f)
            Held.Kettlebell -> add(bell(b.arm), 6.5f)
            Held.Plate, Held.Ball -> add(b.arm.end, 8.5f)
            else -> {}
        }
        parts(b).forEach { add(it.a, it.width / 2); add(it.b, if (it.ring) (it.b - it.a).getDistance() else it.width / 2) }
    }
    cable?.let { add(it, 3f) }
    if (floor) add(Offset(minX, 0f), 1f)
    return Bounds(minX, minY, maxX, maxY)
}

private fun bell(arm: Limb): Offset {
    val d = arm.end - arm.joint
    return arm.end + d / max(d.getDistance(), 0.001f) * 6f
}

// --- Drawing ---

private class Palette(
    val near: Color, val far: Color, val accent: Color, val gear: Color, val floor: Color,
    val cable: Color, val background: Color
)

/** Figure units to pixels: turned, x right, y flipped so the floor is at the bottom. */
private class Frame(val scale: Float, val left: Float, val top: Float, val turn: Float) {
    fun px(p: Offset): Offset {
        val q = p.turned(turn)
        return Offset(left + q.x * scale, top - q.y * scale)
    }
}

private fun fit(b: Bounds, size: Size, turn: Float): Frame {
    val w = b.maxX - b.minX
    val h = b.maxY - b.minY
    val s = min(size.width / w, size.height / h)
    return Frame(s, (size.width - w * s) / 2 - b.minX * s, (size.height - h * s) / 2 + b.maxY * s, turn)
}

private fun DrawScope.drawFigure(f: Figure, b: Body, k: Frame, c: Palette, loads: FloatArray) {
    fun seg(a: Offset, z: Offset, width: Float, color: Color) =
        drawLine(color, k.px(a), k.px(z), width * k.scale, StrokeCap.Round)
    fun dot(p: Offset, r: Float, color: Color) = drawCircle(color, r * k.scale, k.px(p))
    /** A limb segment [wa] wide at [a] narrowing (or widening) to [wz] at [z], with rounded ends. */
    fun taper(a: Offset, z: Offset, wa: Float, wz: Float, color: Color) {
        val pa = k.px(a)
        val pz = k.px(z)
        val ra = wa / 2 * k.scale
        val rz = wz / 2 * k.scale
        val d = pz - pa
        val len = d.getDistance()
        drawCircle(color, ra, pa)
        drawCircle(color, rz, pz)
        if (len < 0.5f) return
        val n = Offset(-d.y / len, d.x / len)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(pa.x + n.x * ra, pa.y + n.y * ra)
            lineTo(pz.x + n.x * rz, pz.y + n.y * rz)
            lineTo(pz.x - n.x * rz, pz.y - n.y * rz)
            lineTo(pa.x - n.x * ra, pa.y - n.y * ra)
            close()
        }
        drawPath(path, color)
    }
    fun load(group: String) = loads.getOrElse(RECOVERY_GROUPS.indexOf(group)) { 0f }
    fun heat(base: Color, group: String, far: Boolean): Color? {
        val l = load(group)
        if (l <= 0f) return null
        val strength = (if (l >= 1f) 1f else if (l >= 0.5f) 0.6f else 0.35f) * (if (far) 0.7f else 1f)
        return lerp(base, c.accent, strength)
    }
    /** A muscle along one side of a limb; [side] 1 is the front of a limb hanging down, 0 the middle. */
    fun muscle(a: Offset, z: Offset, width: Float, side: Float, from: Float, to: Float, color: Color?) {
        color ?: return
        val d = (z - a) / max((z - a).getDistance(), 0.001f)
        val off = Offset(-d.y, d.x) * (side * width * 0.22f)
        seg(lerp(a, z, from) + off, lerp(a, z, to) + off, width * (if (side == 0f) 0.62f else 0.5f), color)
    }
    fun part(p: Part) {
        val color = when (p.role) { Role.Frame -> c.floor; Role.Gear -> c.gear; Role.Cable -> c.cable }
        when {
            p.box -> {
                val a = k.px(p.a)
                val z = k.px(p.b)
                drawRoundRect(
                    color, Offset(min(a.x, z.x), min(a.y, z.y)), Size(abs(z.x - a.x), abs(z.y - a.y)),
                    CornerRadius(2f * k.scale)
                )
            }
            p.ring -> drawCircle(color, (p.b - p.a).getDistance() * k.scale, k.px(p.a), style = Stroke(p.width * k.scale))
            p.a == p.b -> dot(p.a, p.width / 2, color)
            else -> seg(p.a, p.b, p.width, color)
        }
    }

    val front = f.front
    fun arm(l: Limb, far: Boolean) {
        val base = if (far && !front) c.far else c.near
        val dim = far && !front
        // A thin gap in the background colour round the arm, so an arm across the body (same
        // grey) still reads as an arm. Not at the shoulder, where it joins the body.
        if (!far) {
            taper(lerp(l.root, l.joint, 0.4f), l.joint, 6.2f + 2.4f, 5.4f + 2.4f, c.background)
            taper(l.joint, l.end, 5.4f + 2.4f, 3.8f + 2.4f, c.background)
            dot(l.end, 2.9f + 1.2f, c.background)
        }
        taper(l.root, l.joint, 7f, 5.4f, base)
        taper(l.joint, l.end, 5.4f, 3.8f, base)
        heat(base, "Shoulders", dim)?.let { seg(l.root, lerp(l.root, l.joint, 0.3f), 6.2f, it) }
        if (front) {
            muscle(l.root, l.joint, 6.2f, 0f, 0.3f, 0.9f, heat(base, "Biceps", false) ?: heat(base, "Triceps", false))
        } else {
            muscle(l.root, l.joint, 6.2f, 1f, 0.3f, 0.9f, heat(base, "Biceps", dim))
            muscle(l.root, l.joint, 6.2f, -1f, 0.3f, 0.9f, heat(base, "Triceps", dim))
        }
        heat(base, "Forearms", dim)?.let { taper(lerp(l.joint, l.end, 0.12f), lerp(l.joint, l.end, 0.75f), 3.6f, 2.6f, it) }
        dot(l.end, 2.9f, base)
    }

    fun leg(l: Limb, toe: Float, far: Boolean) {
        val base = if (far && !front) c.far else c.near
        val dim = far && !front
        if (front) taper(l.end - dir(toe) * 1f, l.end + dir(toe) * 3.5f, 4f, 3.4f, base)
        else taper(l.end - dir(toe) * 2f, l.end + dir(toe) * 6.2f, 4.4f, 3.2f, base)
        taper(l.joint, l.end, 6.6f, 4.2f, base)
        taper(l.root, l.joint, 9.6f, 6.8f, base)
        if (front) {
            muscle(l.root, l.joint, 8.6f, 0f, 0f, 0.3f, heat(base, "Glutes", false))
            muscle(l.root, l.joint, 8.6f, 0f, 0.15f, 0.88f, heat(base, "Quads", false) ?: heat(base, "Hamstrings", false))
            muscle(l.joint, l.end, 6.4f, 0f, 0.1f, 0.6f, heat(base, "Calves", false))
        } else {
            muscle(l.root, l.joint, 8.6f, -1f, 0f, 0.35f, heat(base, "Glutes", dim))
            muscle(l.root, l.joint, 8.6f, -1f, 0.38f, 0.9f, heat(base, "Hamstrings", dim))
            muscle(l.root, l.joint, 8.6f, 1f, 0.12f, 0.88f, heat(base, "Quads", dim))
            muscle(l.joint, l.end, 6.4f, -1f, 0.1f, 0.6f, heat(base, "Calves", dim))
        }
    }

    fun held(l: Limb, far: Boolean) {
        if (far && f.oneHand) return
        val gear = if (far && !front) lerp(c.gear, c.background, 0.45f) else c.gear
        when (f.held) {
            Held.Dumbbell -> {
                dot(l.end, 3.9f, gear)
                dot(l.end, 1.3f, c.background)
            }
            Held.Kettlebell -> if (!far) {
                val bell = bell(l)
                seg(l.end, bell, 2.2f, gear)
                dot(bell, 5.2f, gear)
            }
            Held.Handle -> dot(l.end, 1.8f, gear)
            else -> {}
        }
    }

    fun plate(at: Offset, r: Float) {
        dot(at, r, c.background)
        drawCircle(c.gear, (r - 1.2f) * k.scale, k.px(at), style = Stroke(2.4f * k.scale))
        dot(at, r * 0.22f, c.gear)
    }

    if (f.floor) {
        drawLine(c.floor, Offset(0f, k.top), Offset(size.width, k.top), 1.2f * k.scale)
        val grounded = listOf(b.leg.end, b.farLeg.end, b.arm.end, b.farArm.end).filter { it.y < 9f }
        if (grounded.isNotEmpty()) {
            val left = grounded.minOf { it.x } - 7f
            val right = grounded.maxOf { it.x } + 9f
            val a = k.px(Offset(left, 0f))
            val z = k.px(Offset(right, 0f))
            drawOval(
                c.floor.copy(alpha = c.floor.alpha * 0.45f),
                Offset(min(a.x, z.x), a.y - 1.6f * k.scale),
                Size(abs(z.x - a.x), 3.2f * k.scale)
            )
        }
    }
    val parts = f.parts(b)
    parts.filter { !it.front }.forEach(::part)
    f.cable?.let { anchor ->
        val towers = if (front && !f.oneHand) listOf(anchor.x, 2 * b.hip.x - anchor.x) else listOf(anchor.x)
        towers.forEach { x -> seg(Offset(x, 0.8f), Offset(x, max(anchor.y + 6f, 30f)), 2.2f, c.floor) }
    }

    fun cables(far: Boolean) {
        val near = f.cable ?: return
        if (far && f.oneHand) return
        // Seen from the front, each hand has its own tower on its side.
        val anchor = if (far && front) Offset(2 * b.hip.x - near.x, near.y) else near
        val l = if (far) b.farArm else b.arm
        seg(anchor, l.end, 1.1f, c.cable)
        if (!far || front) dot(anchor, 2.4f, c.gear)
    }

    if (front) {
        leg(b.farLeg, b.farToe, far = true)
        leg(b.leg, b.toe, far = false)
    } else {
        cables(far = true)
        arm(b.farArm, far = true)
        held(b.farArm, far = true)
        leg(b.farLeg, b.farToe, far = true)
    }

    val tHip = b.hip + b.up * (if (front) 1.5f else 1f)
    val tTop = b.shoulder - b.up * (if (front) 3f else 2.5f)
    if (front) {
        val across = Offset(b.up.y, -b.up.x)
        taper(tHip, tTop, 14f, 17f, c.near)
        heat(c.near, "Chest", false)?.let { col ->
            listOf(1f, -1f).forEach { s -> seg(lerp(tHip, tTop, 0.62f) + across * (3.6f * s), lerp(tHip, tTop, 0.84f) + across * (3.6f * s), 6f, col) }
        }
        heat(c.near, "Back", false)?.let { col ->
            listOf(1f, -1f).forEach { s -> seg(lerp(tHip, tTop, 0.4f) + across * (6f * s), lerp(tHip, tTop, 0.85f) + across * (6f * s), 2.6f, col) }
        }
        heat(c.near, "Core", false)?.let { seg(lerp(tHip, tTop, 0.08f), lerp(tHip, tTop, 0.48f), 5.5f, it) }
    } else {
        dot(b.hip, 5.8f, c.near)
        taper(tHip, tTop, 11.5f, 13.6f, c.near)
        muscle(tHip, tTop, 12.5f, -1f, 0.55f, 0.92f, heat(c.near, "Chest", false))
        muscle(tHip, tTop, 12.5f, 1f, 0.3f, 0.92f, heat(c.near, "Back", false))
        muscle(tHip, tTop, 12.5f, -1f, 0.08f, 0.5f, heat(c.near, "Core", false))
    }
    taper(b.shoulder, lerp(b.shoulder, b.head, 0.7f), 5.4f, 4.4f, c.near)
    dot(b.head, HEAD, c.near)

    if (front) {
        cables(far = true)
        cables(far = false)
        arm(b.farArm, far = true)
        held(b.farArm, far = true)
    } else {
        cables(far = false)
        leg(b.leg, b.toe, far = false)
    }
    arm(b.arm, far = false)
    held(b.arm, far = false)
    // The near plate is outermost, over the hand that grips the bar inside it.
    when (f.held) {
        Held.Barbell, Held.EzBar -> if (front) {
            val mid = lerp(b.arm.end, b.farArm.end, 0.5f)
            val along = (b.arm.end - b.farArm.end).let { if (it.getDistance() < 1f) Offset(1f, 0f) else it / it.getDistance() }
            val across = Offset(-along.y, along.x)
            val half = if (f.held == Held.Barbell) 25f else 18f
            seg(mid - along * (half + 6f), mid + along * (half + 6f), 1.8f, c.gear)
            listOf(1f, -1f).forEach { s ->
                val at = mid + along * (half * s)
                seg(at - across * (PLATE - 2f), at + across * (PLATE - 2f), 4.4f, c.gear)
            }
        } else plate(b.arm.end, if (f.held == Held.Barbell) PLATE else PLATE * 0.8f)
        Held.Plate -> plate(b.arm.end, 7.5f)
        Held.Ball -> dot(b.arm.end + (b.arm.end - b.arm.joint) / FOREARM * 3f, 6.5f, c.gear)
        else -> {}
    }
    parts.filter { it.front }.forEach(::part)
}

private fun paletteFor(P: GymPalette, background: Color) = Palette(
    near = P.textSecondary,
    far = lerp(P.textSecondary, background, 0.45f),
    accent = P.accentPrimary,
    gear = P.textPrimary,
    floor = lerp(P.textTertiary, background, 0.35f),
    cable = P.textTertiary,
    background = background
)

object ExerciseFigures {
    fun has(name: String): Boolean = exerciseKey(name) in figures
}

/**
 * The drawn figure for [name], moving through the exercise when [moving], otherwise held at its
 * most recognisable moment (for small list pictures). Nothing when there's no figure for it.
 * [background] is what it sits on, used to cut out the barbell plates.
 */
@Composable
fun ExerciseFigure(name: String, modifier: Modifier = Modifier, moving: Boolean = true, background: Color = LocalPalette.current.bgControl) {
    val figure = remember(name) { figures[exerciseKey(name)] } ?: return
    val P = LocalPalette.current
    val loads = remember(name) { muscleLoads("", name, ExerciseDatabase.find(name)?.muscle ?: "") }
    val bounds = remember(name) { figure.bounds() }
    val palette = remember(P, background) { paletteFor(P, background) }
    val time: State<Float> = when {
        !moving -> remember(name) { mutableFloatStateOf(figure.thumb) }
        figure.loop > 0 -> rememberInfiniteTransition(label = "figure_loop").animateFloat(
            0f, 1f, infiniteRepeatable(tween(figure.loop, easing = LinearEasing), RepeatMode.Restart), label = "figure_t"
        )
        else -> {
            val t = remember(name) { Animatable(0f) }
            LaunchedEffect(name) {
                // Like a good rep: a brisk lift, a squeeze at the top, a slower controlled lowering.
                while (true) {
                    delay(500)
                    t.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
                    delay(450)
                    t.animateTo(0f, tween(1300, easing = FastOutSlowInEasing))
                }
            }
            t.asState()
        }
    }
    Canvas(modifier) {
        drawFigure(figure, figure.at(time.value), fit(bounds, size, figure.turn), palette, loads)
    }
}

/**
 * A small still of [name]'s figure for lists, or, for exercises without one (the user's own), a
 * plain square with a dumbbell; only where [showEmpty], elsewhere nothing is drawn so those rows
 * don't get a gap.
 */
@Composable
fun ExerciseThumb(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, showEmpty: Boolean = true) {
    val P = LocalPalette.current
    val shape = RoundedCornerShape(size * 0.24f)
    if (ExerciseFigures.has(name)) {
        ExerciseFigure(name, modifier.size(size).clip(shape).background(P.bgControl).padding(size * 0.1f), moving = false)
    } else if (showEmpty) {
        Box(modifier.size(size).clip(shape).background(P.bgControl), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.FitnessCenter, null, tint = P.textTertiary, modifier = Modifier.size(size * 0.42f))
        }
    }
}

/** One moment ([t] from 0 = start to 1 = end) of [name]'s figure in an [area] at the origin, for screenshot tests. */
internal fun DrawScope.drawExerciseFigure(name: String, t: Float, P: GymPalette, area: Size, background: Color = P.bgControl) {
    val figure = figures.getValue(exerciseKey(name))
    val loads = muscleLoads("", name, ExerciseDatabase.find(name)?.muscle ?: "")
    drawFigure(figure, figure.at(t), fit(figure.bounds(), area, figure.turn), paletteFor(P, background), loads)
}

internal val figureNames: Set<String> get() = figures.keys
