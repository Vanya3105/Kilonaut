package com.example.gymtracker

import androidx.compose.ui.geometry.Offset
import kotlin.math.sqrt

/*
 * The poses for every library exercise, drawn by ExerciseFigures.kt (see there for the units).
 * Exercises that move the same way share a builder. Each muscle group is its own function to stay
 * under the JVM's method size limit.
 */

internal val figures: Map<String, Figure> by lazy {
    (chest() + back() + shoulders() + arms() + quads() + hamstringsAndGlutes() + calves() + core() + forearms() + cardio())
        .toMap().mapKeys { exerciseKey(it.key) }
}

private fun p(x: Float, y: Float) = Offset(x, y)

private val SEAT = p(0f, 23f)
private val SEAT_FEET = room(19f, 3f)
private val RACK = onBody(6f, 1f)
private val OVERHEAD = onBody(1f, 28.5f)
private val CURLED = onBody(8.4f, -3.8f)
private val BACK_BAR = onBody(-5f, -1f)
private val CHEST_HOLD = onBody(7f, -7f)
private val HEAD_HOLD = onBody(-5f, 5f)

private val seat = bench(-12f, 12f, 17f)
private val seatBack = seat + pad(-9.5f, 21f, -11.5f, 56f)
/** A seat and backrest seen from the front. */
private val frontSeat = listOf(strut(p(0f, 26f), p(0f, 54f), 19f), pad(-13f, 18.5f, 13f, 18.5f), strut(0f, 16f, 0f, 0.8f))

private fun rails(x: Float) = listOf(strut(x, 0.8f, x, 100f), strut(x + 3f, 0.8f, x + 3f, 100f))

/** A pad along the back of a torso at [torso] degrees from [hip], from [from] to [to] along it. */
private fun backPad(hip: Offset, torso: Float, from: Float = -3f, to: Float = 34f): Part {
    val up = dir(torso)
    val back = Offset(-up.y, up.x)
    return pad(hip + up * from + back * 8.5f, hip + up * to + back * 8.5f)
}

/** An incline pad under the chest (chest-supported rows, spider curls). */
private fun chestPad(hip: Offset, torso: Float): List<Part> {
    val up = dir(torso)
    val front = Offset(up.y, -up.x)
    // Far enough out to show beside the chest rather than hidden behind it.
    val a = hip + up * 3f + front * 10f
    val z = hip + up * 31f + front * 10f
    val mid = (a + z) / 2f
    return listOf(pad(a, z), strut(mid, p(mid.x, 0.8f), 2.4f))
}

/** A bar resting on the floor at [pivot], held at the other end near its plate. */
private fun landmine(pivot: Offset): (Body) -> List<Part> = { b ->
    val hand = b.arm.end
    val d = (pivot - hand) / (pivot - hand).getDistance()
    listOf(strut(pivot, hand, 2.4f, Role.Gear, front = true), knob(hand + d * 7f, 7f, front = true))
}

private fun shinRoller(l: Limb, behind: Boolean = false): Offset {
    val d = (l.end - l.joint) / (l.end - l.joint).getDistance()
    val front = Offset(-d.y, d.x) * (if (behind) -1f else 1f)
    return l.end - d * 3f + front * 5f
}

private fun bar(at: Offset) = listOf(strut(at, at + p(0f, 8f), 1.8f), knob(at, 3f, front = true))

/** A straight body from the ankles at [feet] to shoulders at [shoulderY]; [length] 48 pivots on the knees. */
private fun straightBody(feet: Offset, shoulderY: Float, length: Float = 68f, hipAt: Float = 41f): Pair<Offset, Offset> {
    val dy = shoulderY - feet.y
    val shoulder = p(feet.x + sqrt(length * length - dy * dy), shoulderY)
    return (feet + (shoulder - feet) * (hipAt / length)) to shoulder
}

private fun plankTop(feet: Offset = p(-40f, 7f), handY: Float = 2f): Pose {
    val (hip, shoulder) = straightBody(feet, handY + 29f)
    return Pose(hip, aim(hip, shoulder), 10f, hand = room(shoulder.x + 1.5f, handY), foot = room(feet.x, feet.y), toe = 160f)
}

private fun pushUp(
    feet: Offset = p(-40f, 7f), handY: Float = 2f, farHand: Target? = null, farOut: Float = 0f,
    parts: (Float) -> List<Part> = { emptyList() }
): Figure {
    val (h1, s1) = straightBody(feet, handY + 29f)
    val (h2, s2) = straightBody(feet, handY + 10f)
    val hands = room(s1.x + 1.5f, handY)
    // Archer: the far hand set wide (here, further forward) on a straight arm.
    @Suppress("NAME_SHADOWING") val farHand = farHand ?: if (farOut > 0f) room(s1.x + 1.5f + farOut, handY) else null
    val foot = room(feet.x, feet.y)
    val ground = parts(s1.x + 1.5f)
    return Figure(
        Pose(h1, aim(h1, s1), 10f, hand = hands, foot = foot, farHand = farHand, toe = 160f),
        Pose(h2, aim(h2, s2), 5f, hand = hands, foot = foot, farHand = farHand, toe = 160f),
        elbow = p(-0.6f, 1f), parts = { ground }
    )
}

/** From the bottom, an explosive push that lifts the hands off the floor to clap under the chest. */
private fun clapPushUp(): Figure {
    val feet = p(-40f, 7f)
    val (h1, s1) = straightBody(feet, 12f)
    val (h2, s2) = straightBody(feet, 40f)
    val foot = room(feet.x, feet.y)
    return Figure(
        Pose(h1, aim(h1, s1), 5f, hand = room(s1.x - 0.5f, 2f), foot = foot, toe = 160f),
        Pose(h2, aim(h2, s2), 10f, hand = room(s2.x + 4f, s2.y - 16f), foot = foot, toe = 160f, elbow = p(0.3f, -1f)),
        elbow = p(-0.6f, 1f)
    )
}

private fun kneePushUp(): Figure {
    val knee = p(-26f, 2.5f)
    val (h1, s1) = straightBody(knee, 31f, 48f, 21f)
    val (h2, s2) = straightBody(knee, 12f, 48f, 21f)
    val hands = room(s1.x + 1.5f, 2f)
    val foot = room(knee.x - 16f, knee.y + 12f)
    return Figure(
        Pose(h1, aim(h1, s1), 10f, hand = hands, foot = foot, toe = 200f),
        Pose(h2, aim(h2, s2), 5f, hand = hands, foot = foot, toe = 200f),
        elbow = p(-0.6f, 1f), knee = p(0.2f, -1f)
    )
}

/** Lying on a bench (or the floor), pressing up from the chest. */
private fun lyingPress(
    incline: Float = 0f, held: Held = Held.Barbell, deep: Float = 10f, smith: Boolean = false,
    onFloor: Boolean = false, start: Target = onBody(2f, 28.5f), end: Target? = null,
    elbow: Offset = p(0.5f, -1f), oneHand: Boolean = false
): Figure {
    val torso = -90f + incline
    val hip = when {
        onFloor -> p(20f, 6.5f)
        incline < 0f -> p(20f, 26f)
        else -> p(20f, 22f)
    }
    val up = dir(torso)
    val front = Offset(up.y, -up.x)
    val shoulder = hip + up * 27f
    val low = front * deep - up * 8f
    val feet = room(hip.x + if (onFloor) 15f else 18f, 3f)
    val parts = mutableListOf<Part>()
    if (!onFloor) {
        val back = backPad(hip, torso, -4f, 34f)
        parts += back
        parts += strut(back.b.x + 4f, back.b.y, back.b.x + 4f, 0.8f)
        if (incline > 0f) {
            parts += pad(hip.x - 6f, hip.y - 8.5f, hip.x + 10f, hip.y - 8.5f)
            parts += strut(hip.x + 4f, hip.y - 10f, hip.x + 4f, 0.8f)
        } else parts += strut(back.a.x - 4f, back.a.y, back.a.x - 4f, 0.8f)
    }
    if (smith) parts += rails(shoulder.x - 1.5f)
    return Figure(
        Pose(hip, torso, hand = start, foot = feet, knee = p(0.3f, 1f)),
        Pose(hip, torso, hand = end ?: onBody(low.x, low.y), foot = feet, knee = p(0.3f, 1f)),
        held, oneHand, elbow = elbow, parts = { parts }
    )
}

/** Lying flyes, seen from above: arms open wide and close over the chest. */
private fun lyingFly(held: Held) = Figure(
    Pose(hand = onBody(-3f, 2f), foot = room(6f, 3f), reach = 0.3f),
    Pose(hand = onBody(27f, -3f), foot = room(6f, 3f)),
    held, front = true, turn = 90f, floor = false, elbow = p(0f, -1f),
    parts = { listOf(strut(p(0f, 30f), p(0f, 84f), 19f)) }
)

/** Standing cable flyes, seen from the front. */
private fun fly(start: Target, end: Target, anchor: Offset) = Figure(
    Pose(hand = start, foot = room(6f, 3f)),
    Pose(hand = close(end.x, end.y), foot = room(6f, 3f), reach = 0.4f),
    Held.Handle, front = true, elbow = p(0.3f, -1f), cable = anchor
)

/** Seated, seen from the front (pec deck, machine raises, hip machines). */
private fun frontSeated(
    start: Target, end: Target, held: Held? = Held.Handle, startReach: Float = 1f, endReach: Float = 1f,
    elbow: Offset = p(1f, -0.4f), feet: Float = 9f, endFeet: Float = feet
) = Figure(
    Pose(SEAT, hand = start, foot = room(feet, 3f), thigh = 0.3f, knee = p(1f, 0.6f), reach = startReach),
    Pose(SEAT, hand = end, foot = room(endFeet, 3f), thigh = 0.3f, knee = p(1f, 0.6f), reach = endReach),
    held, front = true, thumb = 1f, elbow = elbow, parts = { frontSeat }
)

private fun seatedPush(end: Target) = Figure(
    Pose(SEAT, -6f, hand = onBody(9f, -5f), foot = SEAT_FEET),
    Pose(SEAT, -6f, hand = end, foot = SEAT_FEET),
    Held.Handle, elbow = p(-0.3f, -1f), parts = { seatBack }
)

private fun dips(rings: Boolean = false, extra: (Body) -> List<Part> = { emptyList() }): Figure {
    val at = p(4f, 55f)
    val hold = if (rings) listOf(strut(at, p(at.x, 110f), 1.1f, Role.Cable), knob(at, 2.6f, front = true))
    else listOf(strut(at.x, 0.8f, at.x, at.y), knob(at, 2.2f, front = true))
    return Figure(
        Pose(p(4f, 57f), 8f, hand = room(at.x, at.y), foot = onBody(-8f, -33f), toe = 150f),
        Pose(p(-1f, 44f), 18f, hand = room(at.x, at.y), foot = onBody(-8f, -33f), toe = 150f),
        elbow = p(-1f, 0.3f), knee = p(1f, 0f), parts = { b -> hold + extra(b) }
    )
}

private val beltWeight: (Body) -> List<Part> = { b ->
    listOf(strut(b.hip, b.hip + p(3f, -13f), 1.1f, Role.Cable), knob(b.hip + p(3f, -18f), 5f))
}
private val kneePad: (Body) -> List<Part> = { b ->
    val k = b.leg.joint
    listOf(pad(k + p(-5f, -4f), k + p(4f, -4f)), strut(k + p(0f, -5f), p(k.x, 0.8f)))
}

private fun chest() = listOf(
    "Barbell Bench Press" to lyingPress(),
    "Incline Barbell Bench Press" to lyingPress(30f),
    "Decline Barbell Bench Press" to lyingPress(-15f),
    "Wide-Grip Bench Press" to lyingPress(deep = 11f),
    "Barbell Floor Press" to lyingPress(onFloor = true, deep = 8f),
    "Dumbbell Bench Press" to lyingPress(held = Held.Dumbbell, deep = 6f),
    "Neutral-Grip Dumbbell Press" to lyingPress(held = Held.Dumbbell, deep = 6f),
    "Incline Dumbbell Press" to lyingPress(30f, Held.Dumbbell, 6f),
    "Decline Dumbbell Press" to lyingPress(-15f, Held.Dumbbell, 6f),
    "Dumbbell Floor Press" to lyingPress(held = Held.Dumbbell, deep = 6f, onFloor = true),
    "Kettlebell Floor Press" to lyingPress(held = Held.Kettlebell, deep = 6f, onFloor = true),
    "Smith Machine Bench Press" to lyingPress(smith = true),
    "Smith Machine Incline Press" to lyingPress(30f, smith = true),
    "Machine Chest Press" to seatedPush(onBody(28f, -4f)),
    "Incline Machine Press" to seatedPush(onBody(23f, 14f)),
    "Cable Chest Press" to Figure(
        Pose(p(-3f, 42f), 8f, hand = onBody(9f, -4f), foot = room(10f, 3f), farFoot = room(-18f, 4f), farToe = 120f),
        Pose(p(-3f, 42f), 8f, hand = onBody(28f, -3f), foot = room(10f, 3f), farFoot = room(-18f, 4f), farToe = 120f),
        Held.Handle, elbow = p(-0.5f, -1f), cable = p(-26f, 66f)
    ),
    "Pec Deck" to frontSeated(onBody(15f, 13f), close(2f, 12f), endReach = 0.4f),
    "Cable Fly" to fly(onBody(25f, 4f), onBody(-7f, -4f), p(42f, 74f)),
    "Low-to-High Cable Fly" to fly(onBody(20f, -18f), onBody(-4f, 3f), p(42f, 8f)),
    "High-to-Low Cable Fly" to fly(onBody(20f, 16f), onBody(-7f, -14f), p(42f, 98f)),
    "Dumbbell Fly" to lyingFly(Held.Dumbbell),
    "Incline Dumbbell Fly" to lyingFly(Held.Dumbbell),
    "Incline Cable Fly" to lyingFly(Held.Handle),
    "Push-Up" to pushUp(),
    "Wide Push-Up" to pushUp(),
    "Archer Push-Up" to pushUp(farOut = 16f),
    "Clap Push-Up" to clapPushUp(),
    "Incline Push-Up" to pushUp(handY = 20f) { x -> bench(x - 10f, x + 12f, 18f) },
    "Decline Push-Up" to pushUp(feet = p(-40f, 22f)) { bench(-48f, -30f, 18f) },
    "Knee Push-Up" to kneePushUp(),
    "Single-Arm Push-Up" to pushUp(farHand = onBody(-15f, 4f)),
    "Ring Push-Up" to pushUp(handY = 9f) { x -> listOf(strut(p(x, 9f), p(x, 55f), 1.1f, Role.Cable), knob(p(x, 9f), 2.6f, front = true)) },
    "Dips" to dips(),
    "Weighted Dips" to dips(extra = beltWeight),
    "Assisted Dip" to dips(extra = kneePad),
    "Dumbbell Pullover" to Figure(
        Pose(p(20f, 22f), -90f, hand = onBody(0f, 28.5f), foot = room(38f, 3f), knee = p(0.3f, 1f)),
        Pose(p(20f, 22f), -90f, hand = onBody(-27f, -4f), foot = room(38f, 3f), knee = p(0.3f, 1f)),
        Held.Dumbbell, oneHand = true, thumb = 1f, elbow = p(-0.3f, 1f), parts = { bench(-22f, 34f) }
    ),
    "Svend Press" to Figure(
        Pose(hand = onBody(7f, -8f)), Pose(hand = onBody(27f, -7f)),
        Held.Plate, elbow = p(-0.5f, -1f)
    )
)

// --- Back ---

private val HANG_FROM_BAR = Pose(p(6.5f, 43f), -5f, hand = room(6f, 98f), foot = onBody(-7f, -34f), toe = 160f)
private val CHIN_OVER_BAR = Pose(p(8f, 69f), -12f, head = 5f, hand = room(6f, 98f), foot = onBody(-7f, -34f), toe = 160f)

private fun hanging(
    end: Pose = CHIN_OVER_BAR, start: Pose = HANG_FROM_BAR, elbow: Offset = p(0.6f, -1f),
    thumb: Float = 1f, extra: (Body) -> List<Part> = { emptyList() }
) = Figure(start, end, thumb = thumb, elbow = elbow, knee = p(1f, 0f), floor = false, parts = { b -> bar(p(6f, 100f)) + extra(b) })

private fun legsUp(end: Target, torso: Float = -5f) = hanging(
    HANG_FROM_BAR.copy(torso = torso, foot = end, toe = 60f),
    HANG_FROM_BAR.copy(foot = onBody(1f, -40.5f), toe = 150f), elbow = p(0.6f, -1f)
)

/** Arms straight up, then the bar to the upper chest, leaning back a little with the chest up and elbows driving down. */
private fun pulldown(end: Target = onBody(9f, -6f), oneHand: Boolean = false): Figure {
    val rest = if (oneHand) onBody(4f, -26f) else null
    return Figure(
        Pose(SEAT, -3f, -4f, hand = onBody(4f, 28f), foot = SEAT_FEET, farHand = rest),
        Pose(SEAT, -17f, 10f, hand = end, foot = SEAT_FEET, farHand = rest, elbow = p(-0.5f, -1f)),
        Held.Handle, oneHand, thumb = 1f, elbow = p(0.2f, -1f), cable = p(6f, 106f),
        parts = { b ->
            seat + pad(b.leg.joint + p(-4f, 6f), b.leg.joint + p(5f, 6f)) +
                (if (oneHand) emptyList() else listOf(knob(b.arm.end, 3f, front = true)))
        }
    )
}

/** Bent over, pulling from straight arms to the belly. */
private fun row(
    held: Held? = Held.Barbell, torso: Float = 70f, hip: Offset = p(-9f, 38f), oneHand: Boolean = false,
    start: Target = onBody(0f, -28f), farHand: Target? = null, parts: (Body) -> List<Part> = { emptyList() }
): Figure {
    val up = dir(torso)
    val front = Offset(up.y, -up.x)
    val belly = up * 10f + front * 7f - up * 27f
    return Figure(
        Pose(hip, torso, -40f, hand = start, farHand = farHand),
        Pose(hip, torso, -40f, hand = onBody(belly.x, belly.y), farHand = farHand),
        held, oneHand, thumb = 1f, elbow = p(-0.6f, 1f), parts = parts
    )
}

private fun chestSupported(held: Held?) = row(held, 50f, p(-4f, 40f), parts = { chestPad(p(-4f, 40f), 50f) })

private fun shrug(held: Held, cable: Offset? = null) = Figure(
    Pose(hand = HANG), Pose(hand = HANG, squash = 1.16f), held, thumb = 1f, cable = cable
)

private fun deadlift(lift: Float = 0f, hand: Offset = p(4f, 11f), hip: Offset = p(-12f, 24f), torso: Float = 62f, parts: List<Part> = emptyList()) = Figure(
    Pose(hip + p(0f, lift), torso, -30f, hand = room(hand.x, hand.y + lift), foot = room(0f, 3f + lift)),
    Pose(p(-1f, 43.5f + lift), -3f, hand = room(-1.5f, 43f + lift), foot = room(0f, 3f + lift)),
    Held.Barbell, elbow = p(-1f, 0f), parts = { parts }
)

private fun invertedRow(rings: Boolean) = Figure(
    Pose(p(25.3f, 7.9f), -83.4f, hand = room(0f, 40f), foot = room(66f, 3f), toe = 10f),
    Pose(p(29.2f, 21.1f), -63.8f, hand = room(0f, 40f), foot = room(66f, 3f), toe = 10f),
    thumb = 1f, elbow = p(0.5f, -1f), knee = p(0f, 1f),
    parts = {
        if (rings) listOf(strut(p(0f, 40f), p(0f, 100f), 1.1f, Role.Cable), knob(p(0f, 40f), 2.6f, front = true))
        else listOf(strut(-4f, 0.8f, -4f, 40f), knob(p(0f, 40f), 2.3f, front = true))
    }
)

private fun back() = listOf(
    "Pull-Up" to hanging(),
    "Chin-Up" to hanging(),
    "Neutral-Grip Pull-Up" to hanging(),
    "Negative Pull-Up" to hanging(),
    "Weighted Pull-Up" to hanging(extra = beltWeight),
    "Assisted Pull-Up" to hanging(extra = kneePad),
    "Scapular Pull-Up" to hanging(HANG_FROM_BAR.copy(hip = p(6.5f, 46f))),
    // Pull high, roll the chest over the bar with the elbows up behind, then press out to straight arms.
    "Muscle-Up" to Figure(
        HANG_FROM_BAR,
        Pose(p(1f, 99f), 8f, hand = room(6f, 98f), foot = onBody(-4f, -38f), toe = 160f, elbow = p(-1f, 0.2f)),
        thumb = 1f, elbow = p(0.6f, -1f), knee = p(1f, 0f), floor = false,
        mid = Pose(p(3f, 74f), 28f, -10f, hand = room(6f, 98f), foot = onBody(-8f, -36f), toe = 160f, elbow = p(-1f, 0.6f)),
        parts = { bar(p(6f, 100f)) }
    ),
    "Rope Climb" to Figure(
        Pose(p(2f, 45f), -8f, hand = room(8f, 92f), farHand = room(8f, 80f), foot = room(6f, 22f), toe = 150f),
        Pose(p(2f, 56f), -8f, hand = room(8f, 106f), farHand = room(8f, 94f), foot = room(6f, 36f), toe = 150f),
        thumb = 1f, elbow = p(0.5f, -1f), knee = p(1f, 0f), floor = false,
        parts = { listOf(strut(8f, 10f, 8f, 125f, 2.6f, Role.Gear)) }
    ),
    "Inverted Row" to invertedRow(rings = false),
    "Ring Row" to invertedRow(rings = true),
    "Lat Pulldown" to pulldown(),
    "Close-Grip Lat Pulldown" to pulldown(),
    "Underhand Lat Pulldown" to pulldown(),
    "Single-Arm Lat Pulldown" to pulldown(oneHand = true),
    "Straight-Arm Pulldown" to Figure(
        Pose(p(-4f, 42f), 25f, hand = onBody(22f, 17f), foot = room(2f, 3f)),
        Pose(p(-4f, 42f), 25f, hand = onBody(5f, -27f), foot = room(2f, 3f)),
        Held.Handle, thumb = 1f, elbow = p(-0.3f, -1f), cable = p(36f, 96f)
    ),
    "Machine Pullover" to Figure(
        Pose(SEAT, -6f, hand = onBody(4f, 27f), foot = SEAT_FEET),
        Pose(SEAT, -6f, hand = onBody(22f, -8f), foot = SEAT_FEET),
        Held.Handle, thumb = 1f, elbow = p(0f, -1f), parts = { seatBack }
    ),
    "Barbell Row" to row(),
    "Underhand Barbell Row" to row(),
    "Pendlay Row" to row(torso = 85f, hip = p(-12f, 34f), start = onBody(0f, -25.5f)),
    "Bent-Over Dumbbell Row" to row(Held.Dumbbell),
    "Kettlebell Row" to row(Held.Kettlebell, oneHand = true, farHand = onBody(6f, -20f)),
    "T-Bar Row" to row(Held.Handle, parts = landmine(p(-46f, 1f))),
    "Meadows Row" to row(Held.Handle, torso = 60f, oneHand = true, farHand = onBody(8f, -22f), parts = landmine(p(-46f, 1f))),
    "Dumbbell Row" to Figure(
        Pose(p(-6f, 40f), 85f, -40f, hand = onBody(0f, -27f), farHand = room(22f, 18f), foot = room(4f, 3f), farFoot = room(-24f, 21f), farToe = 260f, farKnee = p(0.3f, -1f)),
        Pose(p(-6f, 40f), 85f, -40f, hand = onBody(-12f, -8f), farHand = room(22f, 18f), foot = room(4f, 3f), farFoot = room(-24f, 21f), farToe = 260f, farKnee = p(0.3f, -1f)),
        Held.Dumbbell, oneHand = true, thumb = 1f, elbow = p(-0.5f, 1f), parts = { bench(-30f, 30f, 18f) }
    ),
    "Renegade Row" to plankTop().let { top ->
        val x = top.hand.x
        Figure(
            top.copy(hand = room(x, 4f), farHand = room(x - 2.5f, 4f)),
            top.copy(hand = room(x - 10f, 22f), farHand = room(x - 2.5f, 4f)),
            Held.Dumbbell, thumb = 1f, elbow = p(-0.5f, 1f)
        )
    },
    "Chest-Supported Row" to chestSupported(Held.Handle),
    "Chest-Supported Dumbbell Row" to chestSupported(Held.Dumbbell),
    "Seated Cable Row" to Figure(
        Pose(p(0f, 10f), 18f, hand = onBody(24f, -6f), foot = room(36f, 9f), toe = 25f, knee = p(0.3f, 1f)),
        Pose(p(0f, 10f), -6f, hand = onBody(5f, -14f), foot = room(36f, 9f), toe = 25f, knee = p(0.3f, 1f)),
        Held.Handle, thumb = 1f, elbow = p(-1f, -0.3f), cable = p(58f, 10f),
        parts = { listOf(pad(-14f, 2.5f, 14f, 2.5f), strut(41f, 0.8f, 41f, 20f, 3f)) }
    ),
    "Single-Arm Cable Row" to Figure(
        Pose(p(0f, 10f), 18f, hand = onBody(24f, -6f), farHand = onBody(6f, -20f), foot = room(36f, 9f), toe = 25f, knee = p(0.3f, 1f)),
        Pose(p(0f, 10f), -6f, hand = onBody(5f, -14f), farHand = onBody(6f, -20f), foot = room(36f, 9f), toe = 25f, knee = p(0.3f, 1f)),
        Held.Handle, oneHand = true, thumb = 1f, elbow = p(-1f, -0.3f), cable = p(58f, 10f),
        parts = { listOf(pad(-14f, 2.5f, 14f, 2.5f), strut(41f, 0.8f, 41f, 20f, 3f)) }
    ),
    "Machine Row" to Figure(
        Pose(SEAT, 0f, hand = onBody(24f, -4f), foot = SEAT_FEET),
        Pose(SEAT, 0f, hand = onBody(2f, -10f), foot = SEAT_FEET),
        Held.Handle, thumb = 1f, elbow = p(-1f, 0f), parts = { seat + pad(10.5f, 30f, 10.5f, 46f) + strut(10.5f, 30f, 10.5f, 0.8f) }
    ),
    "Deadlift" to deadlift(),
    "Deficit Deadlift" to deadlift(lift = 4f, hip = p(-13f, 22f), torso = 64f, hand = p(4f, 7f), parts = listOf(pad(-14f, 2.25f, 14f, 2.25f))),
    // The handles are at the sides, so the weight sits over mid-foot: more knee bend, a more upright back.
    "Trap Bar Deadlift" to deadlift(hip = p(-9f, 25f), torso = 45f, hand = p(0.5f, 14f)),
    "Rack Pull" to Figure(
        Pose(p(-6f, 38f), 35f, -15f, hand = room(6f, 32f), foot = room(0f, 3f)),
        Pose(p(-1f, 43.5f), -3f, hand = room(-1.5f, 43f), foot = room(0f, 3f)),
        Held.Barbell, elbow = p(-1f, 0f), parts = { listOf(strut(12f, 0.8f, 12f, 21f, 2.4f), knob(p(12f, 21f), 2f, Role.Frame)) }
    ),
    "Back Extension" to Figure(
        Pose(p(0f, 40f), 150f, hand = onBody(3f, -5f), foot = room(-28f, 11f), toe = 200f, knee = p(1f, -0.3f)),
        Pose(p(0f, 40f), 45f, hand = onBody(3f, -5f), foot = room(-28f, 11f), toe = 200f, knee = p(1f, -0.3f)),
        thumb = 1f, elbow = p(0f, -1f),
        parts = { listOf(pad(4f, 35f, -2f, 29f), knob(p(-26f, 15f), 3f, Role.Frame), strut(-28f, 0.8f, 0f, 30f, 2.4f)) }
    ),
    "Superman Hold" to Figure(
        Pose(p(0f, 6.5f), 88f, -4f, hand = onBody(28f, 0f), foot = room(-40f, 6f), toe = 180f),
        // Chest, arms and legs all clearly off the floor.
        Pose(p(0f, 7f), 74f, -12f, hand = onBody(26f, 11f), foot = room(-39f, 16f), toe = 180f),
        elbow = p(0f, 1f), knee = p(0f, -1f)
    ),
    "Barbell Shrug" to shrug(Held.Barbell),
    "Dumbbell Shrug" to shrug(Held.Dumbbell),
    "Cable Shrug" to shrug(Held.Handle, p(12f, 5f)),
    "Kettlebell High Pull" to Figure(
        Pose(p(-12f, 34f), 55f, -30f, hand = onBody(0f, -28f), foot = room(0f, 3f)),
        Pose(hand = close(5f, -4f), foot = room(0f, 3f)),
        Held.Kettlebell, thumb = 1f, elbow = p(-0.3f, 1f)
    )
)

// --- Shoulders ---

/** From the shoulders to overhead, the head back to let the weight past the face. [dip]: knees bend and drive first. */
private fun standPress(held: Held?, start: Target = RACK, dip: Boolean = false, farHand: Target? = null, mid: Pose? = null) = Figure(
    Pose(STAND, -2f, -8f, hand = start, farHand = farHand),
    Pose(STAND, -4f, hand = OVERHEAD, farHand = farHand),
    held, thumb = 1f, elbow = p(1f, -1f),
    mid = mid ?: if (dip) Pose(p(0f, 38.5f), -2f, -8f, hand = start, farHand = farHand, knee = p(1f, 0f)) else null
)

private fun seatPress(held: Held?, start: Target = onBody(7f, 0f), smith: Boolean = false) = Figure(
    Pose(SEAT, -4f, hand = start, foot = SEAT_FEET),
    Pose(SEAT, -4f, hand = OVERHEAD, foot = SEAT_FEET),
    held, thumb = 1f, elbow = p(1f, -1f), parts = { if (smith) seatBack + rails(1.5f) else seatBack }
)

/** Arms out to the sides, seen from the front. */
private fun raise(held: Held?, start: Target = onBody(2f, -28f), end: Target = onBody(28f, -1f), cable: Offset? = null, oneHand: Boolean = false, farHand: Target? = null, torso: Float = 0f, squash: Float = 1f, hip: Offset = STAND, reach: Float = 1f) = Figure(
    Pose(hip, torso, hand = start, foot = room(6f, 3f), farHand = farHand, squash = squash, reach = reach),
    Pose(hip, torso, hand = end, foot = room(6f, 3f), farHand = farHand, squash = squash),
    held, oneHand, front = true, thumb = 1f, elbow = p(0.3f, -1f), cable = cable
)

private fun frontRaise(held: Held, cable: Offset? = null, oneHand: Boolean = false) = Figure(
    Pose(hand = HANG), Pose(hand = onBody(28.5f, 1f)), held, oneHand, thumb = 1f, elbow = p(-0.2f, -1f), cable = cable
)

private fun uprightRow(held: Held, cable: Offset? = null) = Figure(
    Pose(hand = onBody(2f, -27f)), Pose(hand = close(7f, -2f)), held, thumb = 1f, elbow = p(-0.3f, 1f), cable = cable
)

private fun rotation(held: Held, cable: Offset? = null) = Figure(
    Pose(hand = onBody(-8f, -15f), farHand = onBody(-2f, -28f), foot = room(6f, 3f)),
    Pose(hand = onBody(13f, -14f), farHand = onBody(-2f, -28f), foot = room(6f, 3f)),
    held, oneHand = true, front = true, thumb = 1f, elbow = p(0.3f, -1f), cable = cable
)

private fun shoulders() = listOf(
    "Overhead Press" to standPress(Held.Barbell),
    "Push Press" to standPress(Held.Barbell, dip = true),
    "Standing Dumbbell Press" to standPress(Held.Dumbbell, onBody(7f, 0f)),
    "Kettlebell Press" to standPress(Held.Kettlebell, farHand = HANG),
    // From the hang, cleaned to the shoulders (catching with the knees bent), then pressed.
    "Clean and Press" to standPress(Held.Barbell, onBody(3f, -27f), mid = Pose(p(0f, 39f), -2f, -8f, hand = close(6f, 1f), knee = p(1f, 0f))),
    // Up into an upright row, the forearms turn up, then pressed.
    "Cuban Press" to standPress(Held.Dumbbell, HANG, mid = Pose(STAND, 0f, hand = close(7f, -1f), elbow = p(-0.3f, 1f))),
    "Seated Dumbbell Shoulder Press" to seatPress(Held.Dumbbell),
    "Machine Shoulder Press" to seatPress(Held.Handle),
    "Smith Machine Shoulder Press" to seatPress(Held.Barbell, RACK, smith = true),
    "Seated Barbell Press" to seatPress(Held.Barbell, RACK),
    "Arnold Press" to seatPress(Held.Dumbbell, onBody(8f, -3f)),
    "Landmine Press" to Figure(
        Pose(p(-2f, 42f), 6f, hand = onBody(6f, -2f), farHand = HANG, foot = room(8f, 3f), farFoot = room(-16f, 4f), farToe = 120f),
        Pose(p(-2f, 42f), 6f, hand = onBody(22f, 16f), farHand = HANG, foot = room(8f, 3f), farFoot = room(-16f, 4f), farToe = 120f),
        oneHand = true, thumb = 1f, elbow = p(0f, -1f), parts = landmine(p(62f, 1f))
    ),
    "Lateral Raise" to raise(Held.Dumbbell),
    "Cable Lateral Raise" to raise(Held.Handle, cable = p(-16f, 6f), oneHand = true, farHand = onBody(-2f, -28f)),
    "Lean-Away Lateral Raise" to Figure(
        Pose(p(-3f, 43f), 12f, hand = onBody(2f, -28f), farHand = room(-22f, 66f), foot = room(2f, 3f), farFoot = room(-6f, 3f)),
        Pose(p(-3f, 43f), 12f, hand = onBody(28f, -1f), farHand = room(-22f, 66f), foot = room(2f, 3f), farFoot = room(-6f, 3f)),
        Held.Dumbbell, oneHand = true, front = true, thumb = 1f, elbow = p(0.3f, -1f),
        parts = { listOf(strut(-24f, 0.8f, -24f, 80f, 2.6f)) }
    ),
    "Machine Lateral Raise" to frontSeated(onBody(3f, -22f), onBody(25f, -3f)),
    "Cable Y-Raise" to raise(Held.Handle, onBody(-5f, -26f), onBody(15f, 25f), p(-18f, 6f)),
    "Rear Delt Fly" to raise(Held.Dumbbell, onBody(-2f, -27f), onBody(27f, -4f), hip = p(0f, 40f), squash = 0.55f),
    "Reverse Pec Deck" to frontSeated(onBody(4f, 1f), onBody(28f, 1f), startReach = 0.4f),
    "Cable Rear Delt Fly" to raise(Held.Handle, onBody(4f, 1f), onBody(28f, 1f), p(-34f, 74f), reach = 0.4f),
    "Front Raise" to frontRaise(Held.Dumbbell),
    "Cable Front Raise" to frontRaise(Held.Handle, p(-8f, 4f)),
    "Plate Front Raise" to frontRaise(Held.Plate, oneHand = true),
    "Face Pull" to Figure(
        Pose(p(-3f, 43f), -6f, hand = onBody(27f, 4f), foot = room(2f, 3f)),
        Pose(p(-3f, 43f), -8f, hand = onBody(5f, 9f), foot = room(2f, 3f)),
        Held.Handle, thumb = 1f, elbow = p(-0.3f, 1f), cable = p(42f, 76f)
    ),
    "Upright Row" to uprightRow(Held.Barbell),
    "Dumbbell Upright Row" to uprightRow(Held.Dumbbell),
    "Cable Upright Row" to uprightRow(Held.Handle, p(9f, 4f)),
    "Pike Push-Up" to Figure(
        Pose(p(-2f, 46f), 138f, hand = room(20f, 2f), foot = room(-20f, 7f), toe = 160f),
        Pose(p(1f, 36f), 144f, hand = room(20f, 2f), foot = room(-20f, 7f), toe = 160f),
        thumb = 1f, elbow = p(-1f, 0f), knee = p(0f, 1f)
    ),
    "Handstand Push-Up" to Figure(
        Pose(p(0f, 60f), 180f, hand = room(2f, 2f), foot = onBody(0f, 40.5f), toe = 0f),
        Pose(p(0f, 45f), 180f, hand = room(2f, 2f), foot = onBody(0f, 40.5f), toe = 0f),
        thumb = 1f, elbow = p(1f, 0f), parts = { listOf(strut(-8f, 0.8f, -8f, 104f)) }
    ),
    "Handstand Hold" to Figure(
        Pose(p(0f, 60f), 180f, hand = room(2f, 2f), foot = onBody(0f, 40.5f), toe = 0f),
        Pose(p(0.6f, 60f), 181f, hand = room(2f, 2f), foot = onBody(0f, 40.5f), toe = 0f),
        elbow = p(1f, 0f)
    ),
    "Cable External Rotation" to rotation(Held.Handle, p(-26f, 46f)),
    "Dumbbell External Rotation" to rotation(Held.Dumbbell),
    "Kettlebell Halo" to Figure(
        Pose(hand = crank(0f, 86f, 9f), farHand = crank(0f, 86f, 9f), foot = room(6f, 3f)),
        held = Held.Kettlebell, front = true, loop = 2400, thumb = 0.25f, elbow = p(1f, -0.5f)
    ),
    "Turkish Get-Up" to Figure(
        // Lying with the bell pressed up, up onto the far hand, then the far leg sweeps under to a half-kneel.
        Pose(p(0f, 6.5f), -90f, hand = onBody(0f, 28.5f), farHand = room(-14f, 2f), foot = room(16f, 3f), farFoot = room(40f, 3f), farToe = 10f, knee = p(0.3f, 1f)),
        Pose(p(-2f, 23.5f), 0f, -4f, hand = onBody(0f, 28.5f), farHand = room(-1f, 24.5f), foot = room(16f, 3f), farFoot = room(-20f, 4f), farToe = 260f, knee = p(0.3f, 1f), farKnee = p(0.3f, -1f)),
        Held.Kettlebell, thumb = 1f, elbow = p(0f, 1f),
        mid = Pose(p(0f, 6.5f), -25f, hand = onBody(0f, 28.5f), farHand = room(-22f, 2f), foot = room(16f, 3f), farFoot = room(40f, 3f), farToe = 10f, knee = p(0.3f, 1f))
    ),
    "Kettlebell Snatch" to Figure(
        Pose(p(-13f, 33f), 62f, -30f, hand = onBody(-12f, -26.5f, way = 1), farHand = HANG, foot = room(0f, 3f)),
        Pose(STAND, -4f, hand = OVERHEAD, farHand = HANG, foot = room(0f, 3f)),
        Held.Kettlebell, thumb = 1f, elbow = p(-1f, -1f)
    )
)

// --- Arms ---

private fun curl(
    held: Held? = Held.Dumbbell, start: Target = onBody(1.5f, -28.5f), end: Target = CURLED,
    hip: Offset = STAND, torso: Float = 0f, foot: Target = FEET, oneHand: Boolean = false,
    farHand: Target? = null, farEnd: Target? = farHand, elbow: Offset = p(-1f, -0.3f), cable: Offset? = null,
    parts: (Body) -> List<Part> = { emptyList() }
) = Figure(
    Pose(hip, torso, hand = start, foot = foot, farHand = farHand),
    Pose(hip, torso, hand = end, foot = foot, farHand = farEnd),
    held, oneHand, thumb = 1f, elbow = elbow, cable = cable, parts = parts
)

private fun preacher(held: Held, cable: Offset? = null) = curl(
    held, onBody(24f, -16f), onBody(9f, 5f), SEAT, 8f, SEAT_FEET, elbow = p(1f, -1f), cable = cable,
    // The slanted pad in front of the chest, under the back of the upper arm down to the elbow.
    parts = { seat + strut(5.5f, 43f, 15f, 33f, 5.5f) + strut(11f, 36f, 11f, 17f) }
)

private fun inclineCurl() = curl(
    start = onBody(-3f, -28.5f), end = onBody(4f, -8f), hip = SEAT, torso = -30f, foot = SEAT_FEET,
    parts = { listOf(backPad(SEAT, -30f), pad(-6f, 14.5f, 12f, 14.5f), strut(4f, 12f, 4f, 0.8f)) }
)

private fun pushdown(oneHand: Boolean = false, seated: Boolean = false) = Figure(
    Pose(if (seated) SEAT else STAND, 8f, hand = onBody(11f, -9f), foot = if (seated) SEAT_FEET else room(2f, 3f), farHand = if (oneHand) HANG else null),
    Pose(if (seated) SEAT else STAND, 8f, hand = onBody(6f, -28f), foot = if (seated) SEAT_FEET else room(2f, 3f), farHand = if (oneHand) HANG else null),
    Held.Handle, oneHand, thumb = 1f, elbow = p(-1f, -0.2f), cable = p(14f, if (seated) 80f else 100f),
    parts = { if (seated) seatBack else emptyList() }
)

private fun overheadExtension(oneHand: Boolean) = Figure(
    Pose(hand = onBody(-11f, 10f), farHand = if (oneHand) HANG else null),
    Pose(hand = OVERHEAD, farHand = if (oneHand) HANG else null),
    Held.Dumbbell, oneHand = true, thumb = 1f, elbow = p(0.3f, 1f)
)

private fun arms() = listOf(
    "Barbell Curl" to curl(Held.Barbell),
    "EZ-Bar Curl" to curl(Held.EzBar),
    "Dumbbell Curl" to curl(),
    "Hammer Curl" to curl(),
    "Zottman Curl" to curl(),
    "Cross-Body Hammer Curl" to curl(),
    "Alternating Dumbbell Curl" to curl(farHand = CURLED, farEnd = onBody(1.5f, -28.5f)),
    "Seated Dumbbell Curl" to curl(hip = SEAT, foot = SEAT_FEET, parts = { seat }),
    "Incline Dumbbell Curl" to inclineCurl(),
    "Incline Hammer Curl" to inclineCurl(),
    "Concentration Curl" to curl(
        start = onBody(2f, -27f), end = onBody(9f, -8f), hip = SEAT, torso = 35f, foot = room(22f, 3f),
        oneHand = true, farHand = room(24f, 30f), parts = { seat }
    ),
    "Spider Curl" to curl(
        start = onBody(0f, -28.5f), end = onBody(9f, -12f), hip = p(-4f, 40f), torso = 50f, foot = room(2f, 3f),
        elbow = p(-0.2f, -1f), parts = { chestPad(p(-4f, 40f), 50f) }
    ),
    "Preacher Curl" to preacher(Held.EzBar),
    "Machine Preacher Curl" to preacher(Held.Handle),
    "Machine Bicep Curl" to preacher(Held.Handle),
    "Cable Preacher Curl" to preacher(Held.Handle, p(34f, 4f)),
    "Cable Curl" to curl(Held.Handle, cable = p(14f, 4f)),
    "Rope Hammer Curl" to curl(Held.Handle, cable = p(14f, 4f)),
    "Single-Arm Cable Curl" to curl(Held.Handle, oneHand = true, farHand = HANG, cable = p(14f, 4f)),
    "Bayesian Cable Curl" to curl(
        Held.Handle, onBody(-9f, -27f), onBody(6f, -8f), p(-2f, 43f), foot = room(8f, 3f),
        oneHand = true, farHand = HANG, cable = p(-34f, 12f)
    ),
    "Drag Curl" to curl(Held.Barbell, onBody(3f, -28f), onBody(4f, -8f), elbow = p(-1f, 0f)),
    "Overhead Cable Curl" to Figure(
        Pose(hand = onBody(28.5f, 1f), foot = room(6f, 3f)),
        Pose(hand = onBody(9f, 12f), foot = room(6f, 3f)),
        Held.Handle, front = true, thumb = 1f, elbow = p(0.2f, -1f), cable = p(44f, 72f)
    ),
    "Tricep Pushdown" to pushdown(),
    "Rope Pushdown" to pushdown(),
    "V-Bar Pushdown" to pushdown(),
    "Reverse-Grip Pushdown" to pushdown(),
    "Single-Arm Cable Extension" to pushdown(oneHand = true),
    "Machine Tricep Extension" to pushdown(seated = true),
    "Overhead Cable Tricep Extension" to Figure(
        Pose(p(-2f, 42f), 25f, hand = onBody(-5f, 9f), foot = room(10f, 3f), farFoot = room(-16f, 4f), farToe = 120f),
        Pose(p(-2f, 42f), 25f, hand = onBody(13f, 25f), foot = room(10f, 3f), farFoot = room(-16f, 4f), farToe = 120f),
        Held.Handle, thumb = 1f, elbow = p(0.3f, 1f), cable = p(-28f, 64f)
    ),
    "Overhead Dumbbell Extension" to overheadExtension(oneHand = false),
    "Single-Arm Overhead Extension" to overheadExtension(oneHand = true),
    "Skull Crusher" to lyingPress(held = Held.EzBar, start = onBody(-3f, 28f), end = onBody(-13f, 10f), elbow = p(0.3f, 1f)),
    "Dumbbell Skull Crusher" to lyingPress(held = Held.Dumbbell, start = onBody(-3f, 28f), end = onBody(-13f, 10f), elbow = p(0.3f, 1f)),
    "Close-Grip Bench Press" to lyingPress(deep = 9f),
    "JM Press" to lyingPress(end = onBody(-2f, 13f), elbow = p(0.6f, -1f)),
    "Close-Grip Dumbbell Press" to lyingPress(held = Held.Dumbbell, deep = 8f),
    "Tate Press" to lyingPress(held = Held.Dumbbell, end = onBody(4f, 11f), elbow = p(-0.3f, 1f)),
    "Tricep Dips" to Figure(
        Pose(p(-1f, 21f), -4f, hand = room(-5f, 19f), foot = room(32f, 3f), toe = 20f),
        Pose(p(0f, 8f), -2f, hand = room(-5f, 19f), foot = room(32f, 3f), toe = 20f),
        elbow = p(-1f, 0.3f), knee = p(0f, 1f), parts = { bench(-24f, -2f, 17f) }
    ),
    "Ring Dips" to dips(rings = true),
    "Dumbbell Kickback" to Figure(
        // Knee and hand on a bench, back flat, upper arm held along the body; the forearm swings
        // from hanging down to straight back. The near knee is the one on the bench, so the arm
        // shows against the far, standing leg.
        Pose(p(-6f, 40f), 85f, -40f, hand = onBody(-14f, -19f), farHand = room(22f, 18f), foot = room(-24f, 21f), farFoot = room(4f, 3f), toe = 260f, knee = p(0.3f, -1f), farKnee = p(1f, 0.3f)),
        Pose(p(-6f, 40f), 85f, -40f, hand = onBody(-26f, -12f), farHand = room(22f, 18f), foot = room(-24f, 21f), farFoot = room(4f, 3f), toe = 260f, knee = p(0.3f, -1f), farKnee = p(1f, 0.3f)),
        Held.Dumbbell, oneHand = true, thumb = 1f, elbow = p(-1f, 1f), parts = { bench(-30f, 30f, 18f) }
    ),
    "Diamond Push-Up" to pushUp(),
    "Bodyweight Tricep Extension" to pushUp(feet = p(-52f, 7f), handY = 30f) { x -> listOf(strut(x, 0.8f, x, 30f), knob(p(x, 30f), 2.3f, front = true)) }
)

// --- Legs ---

private fun squat(
    held: Held? = Held.Barbell, hand: Target = BACK_BAR, endHand: Target = hand, upright: Boolean = false,
    oneHand: Boolean = false, elbow: Offset = p(-1f, -1f), parts: (Body) -> List<Part> = { emptyList() }
) = Figure(
    Pose(STAND, if (upright) 2f else 6f, hand = hand, foot = room(2f, 3f)),
    Pose(if (upright) p(-11f, 21f) else p(-15f, 21f), if (upright) 24f else 42f, if (upright) -12f else -25f, hand = endHand, foot = room(2f, 3f)),
    held, oneHand, thumb = 1f, elbow = elbow, parts = parts
)

private fun lunge(held: Held? = Held.Dumbbell, hand: Target = HANG) = Figure(
    Pose(p(-5f, 40f), 2f, hand = hand, foot = room(12f, 3f), farFoot = room(-24f, 7f), farToe = 125f, farKnee = p(0.4f, -1f)),
    Pose(p(-5f, 21f), 2f, hand = hand, foot = room(12f, 3f), farFoot = room(-24f, 7f), farToe = 125f, farKnee = p(0.4f, -1f)),
    held, thumb = 1f, elbow = if (hand == BACK_BAR) p(-1f, -1f) else p(-0.4f, -1f)
)

private fun hack(): Figure {
    val torso = -25f
    val up = dir(torso)
    val back = Offset(-up.y, up.x)
    val start = p(2f, 40f)
    val rail = start + back * 11f
    return Figure(
        Pose(start, torso, hand = onBody(4f, 4f), foot = room(16f, 6f), toe = 70f),
        Pose(start - up * 16f, torso, hand = onBody(4f, 4f), foot = room(16f, 6f), toe = 70f),
        thumb = 1f, elbow = p(-1f, -0.5f),
        parts = { b ->
            listOf(
                strut(rail - up * 40f, rail + up * 45f, 2.4f),
                pad(b.hip - up * 4f + back * 8.5f, b.shoulder + up * 2f + back * 8.5f),
                pad(b.shoulder + up * 4f + back * 4f, b.shoulder + up * 4f - back * 6f),
                pad(8f, 2f, 26f, 8f)
            )
        }
    )
}

private val legPressPlatform: (Body) -> List<Part> = { b ->
    val c = b.leg.end + dir(45f) * 3f
    listOf(pad(c + dir(-45f) * 10f, c - dir(-45f) * 8f), strut(8f, 6f, 52f, 50f, 2.4f), backPad(p(0f, 20f), -60f), pad(-6f, 13.5f, 8f, 13.5f), strut(0f, 11f, 0f, 0.8f))
}

private fun legPress(calves: Boolean = false) = Figure(
    Pose(p(0f, 20f), -60f, hand = room(-6f, 18f), foot = room(if (calves) 30f else 16f, if (calves) 46f else 32f), toe = -45f, knee = p(-0.3f, 1f)),
    Pose(p(0f, 20f), -60f, hand = room(-6f, 18f), foot = room(if (calves) 31.5f else 30f, if (calves) 47.5f else 46f), toe = if (calves) -5f else -45f, knee = p(-0.3f, 1f)),
    thumb = 1f, elbow = p(0f, -1f), parts = legPressPlatform
)

private val legSeat = listOf(pad(-12f, 17.75f, 20f, 17.75f), pad(-9f, 22f, -13f, 56f), strut(4f, 15.5f, 4f, 0.8f))

private fun legMachine(start: Target, end: Target, rollerBehind: Boolean, farFoot: Target? = null) = Figure(
    Pose(p(0f, 26f), -8f, hand = room(-2f, 20f), foot = start, farFoot = farFoot, knee = p(0.1f, 1f)),
    Pose(p(0f, 26f), -8f, hand = room(-2f, 20f), foot = end, farFoot = farFoot, knee = p(0.1f, 1f)),
    thumb = 1f, elbow = p(0f, -1f),
    parts = { b -> legSeat + knob(shinRoller(b.leg, rollerBehind), 3f, Role.Frame, front = true) }
)

/** The balls of the feet on a step: heels sink below it, then up high on the toes. */
private fun calfRaise(held: Held? = null, hand: Target = HANG, oneLeg: Boolean = false, parts: (Body) -> List<Part> = { emptyList() }) = Figure(
    Pose(STAND + p(0f, 1f), hand = hand, foot = room(1f, 4f), toe = 65f, farFoot = if (oneLeg) room(-6f, 15f) else null, farToe = if (oneLeg) 150f else null),
    Pose(STAND + p(1.5f, 7f), hand = hand, foot = room(2.5f, 9.5f), toe = 150f, farFoot = if (oneLeg) room(-4.5f, 21f) else null, farToe = if (oneLeg) 150f else null),
    held, thumb = 1f, elbow = if (hand == BACK_BAR) p(-1f, -1f) else p(-0.4f, -1f),
    parts = { b -> listOf(block(3f, 0f, 18f, 5.5f)) + parts(b) }
)

private fun quads() = listOf(
    "Back Squat" to squat(),
    "Front Squat" to squat(hand = RACK, upright = true, elbow = p(1f, 0f)),
    "Goblet Squat" to squat(Held.Dumbbell, CHEST_HOLD, upright = true, oneHand = true, elbow = p(0.3f, -1f)),
    "Dumbbell Squat" to squat(Held.Dumbbell, HANG, upright = true, elbow = p(-0.4f, -1f)),
    "Kettlebell Front Squat" to squat(Held.Kettlebell, CHEST_HOLD, upright = true, elbow = p(0.3f, -1f)),
    "Overhead Squat" to squat(hand = OVERHEAD, upright = true, elbow = p(0f, 1f)),
    "Box Squat" to squat(parts = { listOf(block(-25f, 0f, -5f, 15f)) }),
    "Smith Machine Squat" to squat(parts = { rails(-5f) }),
    "Bodyweight Squat" to squat(null, HANG, onBody(27f, -4f), elbow = p(-0.3f, -1f)),
    "Belt Squat" to squat(null, onBody(20f, -8f), upright = true, elbow = p(-0.3f, -1f), parts = { b ->
        listOf(strut(b.hip, b.hip + p(0f, -22f), 1.1f, Role.Cable), knob(b.hip + p(0f, -25f), 4.5f))
    }),
    "Jump Squat" to Figure(
        Pose(p(-15f, 21f), 42f, -25f, hand = onBody(-6f, -27f), foot = room(2f, 3f)),
        // Clear of the floor, arms swung up.
        Pose(p(0f, 60f), 2f, hand = onBody(12f, 20f), foot = room(1f, 18f), toe = 145f),
        thumb = 1f, elbow = p(-0.4f, -1f)
    ),
    "Pistol Squat" to Figure(
        Pose(STAND, 4f, hand = onBody(26f, -4f), foot = room(2f, 3f), farFoot = room(8f, 14f), farToe = 60f),
        Pose(p(-12f, 17f), 40f, -25f, hand = onBody(27f, -3f), foot = room(2f, 3f), farFoot = room(28f, 12f), farToe = 30f),
        thumb = 1f, elbow = p(-0.3f, -1f)
    ),
    "Shrimp Squat" to Figure(
        Pose(STAND, 6f, hand = onBody(20f, -12f), foot = room(2f, 3f), farFoot = room(-14f, 30f), farToe = 270f, farKnee = p(0.3f, -1f)),
        Pose(p(-12f, 19f), 35f, -20f, hand = onBody(24f, -8f), foot = room(2f, 3f), farFoot = room(-28f, 8f), farToe = 270f, farKnee = p(0.3f, -1f)),
        thumb = 1f, elbow = p(-0.3f, -1f)
    ),
    "Cossack Squat" to Figure(
        Pose(p(0f, 36f), hand = onBody(-7f, -14f), foot = room(30f, 3f), farFoot = room(-22f, 3f)),
        Pose(p(-12f, 20f), hand = onBody(-7f, -14f), foot = room(30f, 3f), toe = 0f, farFoot = room(-22f, 3f)),
        front = true, thumb = 1f, elbow = p(0.3f, -1f)
    ),
    "Sumo Squat" to Figure(
        Pose(p(0f, 40f), hand = onBody(-8f, -26f), foot = room(17f, 3f), toe = 120f),
        Pose(p(0f, 24f), hand = onBody(-8f, -26f), foot = room(17f, 3f), toe = 120f),
        Held.Dumbbell, oneHand = true, front = true, thumb = 1f, knee = p(1f, 0.2f)
    ),
    "Wall Sit" to Figure(
        Pose(p(0f, 23f), hand = onBody(10f, -20f), foot = room(21f, 3f), knee = p(0f, 1f)),
        elbow = p(-0.3f, -1f), parts = { listOf(strut(-7.5f, 0.8f, -7.5f, 75f, 2.4f)) }
    ),
    "Sissy Squat" to Figure(
        Pose(hand = onBody(4f, -27f), foot = room(2f, 3f)),
        Pose(p(8f, 24f), -32f, 20f, hand = onBody(6f, -24f), foot = room(2f, 5f), toe = 125f, knee = p(1f, 0f)),
        thumb = 1f, elbow = p(-0.3f, -1f)
    ),
    "Hack Squat" to hack(),
    "Pendulum Squat" to hack(),
    "Leg Press" to legPress(),
    "Leg Extension" to legMachine(onBody(21f, -20f), onBody(40f, -2f), rollerBehind = false),
    "Single-Leg Leg Extension" to legMachine(onBody(21f, -20f), onBody(40f, -2f), rollerBehind = false, farFoot = room(18.5f, 6f)),
    "Bulgarian Split Squat" to Figure(
        Pose(p(-5f, 40f), 2f, foot = room(12f, 3f), farFoot = room(-26f, 22f), farToe = 250f, farKnee = p(0.3f, -1f)),
        Pose(p(-5f, 22f), 6f, foot = room(12f, 3f), farFoot = room(-26f, 22f), farToe = 250f, farKnee = p(0.3f, -1f)),
        Held.Dumbbell, thumb = 1f, parts = { bench(-40f, -18f, 18f) }
    ),
    "Walking Lunge" to lunge(),
    "Reverse Lunge" to lunge(),
    "Dumbbell Lunge" to lunge(),
    "Curtsy Lunge" to lunge(),
    "Barbell Lunge" to lunge(Held.Barbell, BACK_BAR),
    "Jumping Lunge" to Figure(
        Pose(p(-5f, 24f), 2f, hand = onBody(-6f, -27f), foot = room(12f, 3f), farFoot = room(-24f, 7f), farToe = 125f, farKnee = p(0.4f, -1f)),
        Pose(p(-5f, 24f), 2f, hand = onBody(6f, -27f), foot = room(-24f, 7f), toe = 125f, knee = p(0.4f, -1f), farFoot = room(12f, 3f), farToe = 90f, farKnee = p(1f, 0.3f)),
        loop = 1100, elbow = p(-0.4f, -1f)
    ),
    "Step-Up" to Figure(
        Pose(p(2f, 40f), 6f, foot = room(18f, 21f), farFoot = room(-4f, 3f)),
        Pose(p(15f, 61.5f), 0f, foot = room(18f, 21f), farFoot = room(12f, 28f), farToe = 120f),
        Held.Dumbbell, thumb = 1f, parts = { listOf(block(4f, 0f, 38f, 18f)) }
    ),
    "Box Jump" to Figure(
        Pose(p(-14f, 22f), 40f, -25f, hand = onBody(-8f, -27f), foot = room(2f, 3f)),
        Pose(p(36f, 64f), 4f, hand = onBody(6f, -26f), foot = room(38f, 24f)),
        thumb = 1f, elbow = p(-0.4f, -1f), parts = { listOf(block(22f, 0f, 56f, 21f)) }
    ),
    "Kettlebell Thruster" to Figure(
        Pose(p(-11f, 21f), 24f, -12f, hand = CHEST_HOLD, foot = room(2f, 3f)),
        Pose(STAND, -2f, hand = OVERHEAD, foot = room(2f, 3f)),
        Held.Kettlebell, thumb = 1f, elbow = p(0.3f, -1f)
    ),
    "Sled Push" to Figure(
        Pose(p(-6f, 36f), 55f, hand = room(30f, 46f), foot = room(6f, 3f), farFoot = room(-24f, 7f), farToe = 130f),
        Pose(p(-6f, 36f), 55f, hand = room(30f, 46f), foot = room(-24f, 7f), toe = 130f, farFoot = room(6f, 3f), farToe = 90f),
        loop = 1100, elbow = p(-0.5f, -1f),
        parts = { listOf(strut(31f, 4f, 31f, 46f, 2.4f), pad(26f, 3f, 54f, 3f), knob(p(44f, 12f), 7f)) }
    ),
    "Power Clean" to Figure(
        Pose(p(-12f, 24f), 62f, -30f, hand = room(4f, 11f), foot = room(0f, 3f), elbow = p(-1f, 0f)),
        CLEAN_CATCH,
        Held.Barbell, thumb = 1f, mid = SECOND_PULL
    ),
    "Hang Clean" to Figure(
        Pose(p(-6f, 40f), 35f, -15f, hand = room(9f, 34f), foot = room(0f, 3f), elbow = p(-1f, 0f)),
        CLEAN_CATCH,
        Held.Barbell, thumb = 1f, mid = SECOND_PULL
    ),
    // Cleaned from the floor to the shoulders, then jerked overhead into a split.
    "Clean and Jerk" to Figure(
        Pose(p(-12f, 24f), 62f, -30f, hand = room(4f, 11f), foot = room(0f, 3f), farFoot = room(-1.5f, 3f), elbow = p(-1f, 0f)),
        // Overhead in room terms, like the catch it comes from (a room and a body target don't mix).
        Pose(p(-4f, 37f), -2f, hand = room(-4.5f, 92f), foot = room(14f, 3f), farFoot = room(-22f, 6f), farToe = 120f, elbow = p(1f, -1f)),
        Held.Barbell, thumb = 1f,
        mid = CLEAN_CATCH.copy(farFoot = room(-1.5f, 3f))
    ),
    "Snatch" to Figure(
        Pose(p(-13f, 25f), 60f, -30f, hand = room(6f, 11f), foot = room(0f, 3f)),
        // Elbows turn up under the bar as it goes overhead.
        Pose(p(-10f, 28f), 18f, -5f, hand = room(-1f, 82f), foot = room(0f, 3f), elbow = p(0f, 1f)),
        Held.Barbell, thumb = 1f, elbow = p(-1f, 0f),
        // A little in front of the hips, so the bar's way up passes the face and not through the shoulder.
        mid = SECOND_PULL.copy(hand = room(9f, 44f))
    )
)

/** Up on the toes with the hips through and the shoulders shrugged, the bar at the hips. */
private val SECOND_PULL = Pose(p(1f, 46f), -6f, hand = room(4f, 41f), foot = room(0f, 4f), toe = 140f, elbow = p(-1f, 0f), squash = 1.06f)

/** The bar on the front of the shoulders, elbows high, caught in a quarter squat. */
private val CLEAN_CATCH = Pose(p(-2f, 38f), 2f, -6f, hand = room(6f, 66f), foot = room(0f, 3f), elbow = p(1f, -0.2f), knee = p(1f, 0f))

private fun hinge(held: Held?, hip: Offset = p(-11f, 41f), torso: Float = 75f, hand: Target = HANG) = Figure(
    Pose(hand = hand), Pose(hip, torso, -35f, hand = hand),
    held, thumb = 1f, elbow = if (hand == BACK_BAR) p(-1f, -1f) else p(-1f, 0f)
)

private val QUAD = Pose(
    p(0f, 23f), 72.8f, 10f, hand = room(26.5f, 2f), foot = room(-20f, 2.5f), toe = 260f, knee = p(0.3f, -1f),
    farFoot = room(-22.5f, 2.5f), farToe = 260f, farKnee = p(0.3f, -1f)
)

private fun hipThrust(held: Held?, parts: List<Part>, farLeg: Boolean = false, onFloor: Boolean = false): Figure {
    val shoulder = if (onFloor) p(-12f, 6.5f) else p(-10f, 22f)
    val low = if (onFloor) p(14f, 7f) else p(14f, 8f)
    val high = if (onFloor) p(14f, 19f) else p(17f, 22f)
    val feet = room(if (onFloor) 28f else 30f, 3f)
    val hands = held != null && held != Held.Handle
    return Figure(
        Pose(low, aim(low, shoulder), hand = if (hands) room(low.x + 1f, low.y + 8f) else room(shoulder.x + 4f, shoulder.y - 4f), foot = feet, knee = p(0.3f, 1f),
            farFoot = if (farLeg) room(low.x + 34f, low.y + 18f) else null, farToe = if (farLeg) 30f else null),
        Pose(high, aim(high, shoulder), hand = if (hands) room(high.x + 2f, high.y + 8f) else room(shoulder.x + 4f, shoulder.y - 4f), foot = feet, knee = p(0.3f, 1f),
            farFoot = if (farLeg) room(high.x + 38f, high.y + 12f) else null, farToe = if (farLeg) 40f else null),
        held, thumb = 1f, elbow = p(0f, 1f), parts = { b -> parts + if (held == Held.Handle) listOf(pad(b.hip + p(-6f, 7.5f), b.hip + p(6f, 7.5f))) else emptyList() }
    )
}

private fun hamstringsAndGlutes() = listOf(
    "Romanian Deadlift" to hinge(Held.Barbell),
    "Dumbbell Romanian Deadlift" to hinge(Held.Dumbbell),
    "Stiff-Leg Deadlift" to hinge(Held.Barbell, p(-8f, 43f), 85f),
    "Good Morning" to hinge(Held.Barbell, p(-9f, 42f), 75f, BACK_BAR),
    "Single-Leg Romanian Deadlift" to Figure(
        Pose(farFoot = room(-1.5f, 3f)),
        Pose(p(-8f, 42f), 80f, -35f, farFoot = room(-48f, 40f), farToe = 200f),
        Held.Dumbbell, thumb = 1f, elbow = p(-1f, 0f)
    ),
    "Kettlebell Clean" to Figure(
        Pose(p(-13f, 33f), 62f, -30f, hand = onBody(-12f, -26.5f, way = 1), farHand = HANG, foot = room(0f, 3f)),
        Pose(STAND, -2f, hand = onBody(5f, -1f), farHand = HANG, foot = room(0f, 3f)),
        Held.Kettlebell, thumb = 1f, elbow = p(0.3f, -1f)
    ),
    "Seated Leg Curl" to legMachine(onBody(40f, -2f), onBody(16f, -21f), rollerBehind = true),
    "Lying Leg Curl" to Figure(
        Pose(p(0f, 24f), 90f, -10f, hand = room(32f, 16f), foot = room(-41f, 25f), toe = 180f, knee = p(0f, -1f)),
        Pose(p(0f, 24f), 90f, -10f, hand = room(32f, 16f), foot = room(-16f, 40f), toe = 230f, knee = p(0f, -1f)),
        thumb = 1f, elbow = p(-0.3f, -1f),
        parts = { b -> bench(-30f, 34f, 18f) + knob(shinRoller(b.leg, behind = true), 3f, Role.Frame, front = true) }
    ),
    "Standing Leg Curl" to Figure(
        Pose(hand = room(18f, 52f), foot = onBody(1f, -40.5f), farFoot = room(-1.5f, 3f)),
        Pose(hand = room(18f, 52f), foot = onBody(-16f, -30f), farFoot = room(-1.5f, 3f), knee = p(0.3f, -1f)),
        thumb = 1f, elbow = p(-0.3f, -1f),
        parts = { b -> listOf(pad(20f, 40f, 20f, 60f), strut(20f, 40f, 20f, 0.8f), knob(shinRoller(b.leg, behind = true), 3f, Role.Frame, front = true)) }
    ),
    "Nordic Curl" to Figure(
        Pose(p(0f, 25f), 0f, hand = onBody(6f, -8f), foot = room(-20f, 4f), toe = 260f, knee = p(0.2f, -1f)),
        Pose(p(18.2f, 14.5f), 60f, hand = onBody(14f, -14f), foot = room(-20f, 4f), toe = 260f, knee = p(-0.2f, -1f)),
        thumb = 1f, elbow = p(-0.3f, -1f),
        parts = { listOf(knob(p(-21f, 8.5f), 2.6f, Role.Frame), pad(-6f, 0f, 6f, 0f)) }
    ),
    "Glute-Ham Raise" to Figure(
        Pose(p(0f, 25f), 0f, hand = onBody(6f, -8f), foot = room(-20f, 4f), toe = 260f, knee = p(0.2f, -1f)),
        Pose(p(18.2f, 14.5f), 60f, hand = onBody(14f, -14f), foot = room(-20f, 4f), toe = 260f, knee = p(-0.2f, -1f)),
        thumb = 1f, elbow = p(-0.3f, -1f),
        parts = { listOf(knob(p(-21f, 8.5f), 2.6f, Role.Frame), pad(-8f, -1f, 8f, -1f), strut(-14f, -1f, -14f, -1f)) }
    ),
    "Stability Ball Leg Curl" to Figure(
        Pose(p(14f, 16f), aim(p(14f, 16f), p(-12f, 6.5f)), hand = room(-4f, 2f), foot = room(40f, 12f), toe = 20f, knee = p(0.3f, 1f)),
        Pose(p(14f, 20f), aim(p(14f, 20f), p(-12f, 6.5f)), hand = room(-4f, 2f), foot = room(28f, 16f), toe = 20f, knee = p(0.3f, 1f)),
        thumb = 1f, elbow = p(0f, 1f), parts = { b -> listOf(knob(b.leg.end + p(1f, -6f), 6.5f, Role.Frame)) }
    ),
    "Sliding Leg Curl" to Figure(
        Pose(p(14f, 14f), aim(p(14f, 14f), p(-12f, 6.5f)), hand = room(-4f, 2f), foot = room(40f, 3f), toe = 10f, knee = p(0.3f, 1f)),
        Pose(p(14f, 18f), aim(p(14f, 18f), p(-12f, 6.5f)), hand = room(-4f, 2f), foot = room(26f, 3f), toe = 30f, knee = p(0.3f, 1f)),
        thumb = 1f, elbow = p(0f, 1f)
    ),
    "Hip Thrust" to hipThrust(Held.Barbell, bench(-34f, -8f)),
    "Machine Hip Thrust" to hipThrust(Held.Handle, bench(-34f, -8f)),
    "Single-Leg Hip Thrust" to hipThrust(null, bench(-34f, -8f), farLeg = true),
    "Glute Bridge" to hipThrust(Held.Barbell, emptyList(), onFloor = true),
    "Bodyweight Glute Bridge" to hipThrust(null, emptyList(), onFloor = true),
    "Single-Leg Glute Bridge" to hipThrust(null, emptyList(), farLeg = true, onFloor = true),
    "Cable Kickback" to Figure(
        Pose(p(-3f, 43f), 24f, hand = room(22f, 58f), foot = onBody(3f, -39f), farFoot = room(-1.5f, 3f), knee = p(1f, -0.2f)),
        // Driven well back, past the line of the leaning body: the squeeze at the top.
        Pose(p(-3f, 42f), 30f, hand = room(22f, 58f), foot = onBody(-33f, -23f), farFoot = room(-1.5f, 3f), knee = p(0.3f, -1f)),
        thumb = 1f, elbow = p(-0.3f, -1f), knee = p(1f, 0f),
        parts = { b -> listOf(strut(26f, 0.8f, 26f, 70f), strut(p(26f, 4f), b.leg.end, 1.1f, Role.Cable), knob(p(26f, 4f), 2.4f)) }
    ),
    "Cable Pull-Through" to Figure(
        Pose(p(-10f, 40f), 70f, -35f, hand = onBody(-10f, -27f), foot = room(4f, 3f)),
        Pose(hand = onBody(6f, -25f), foot = room(4f, 3f)),
        Held.Handle, thumb = 1f, elbow = p(-0.3f, -1f), cable = p(-36f, 5f)
    ),
    "Hip Abduction Machine" to frontSeated(onBody(3f, -20f), onBody(3f, -20f), feet = 6f, endFeet = 26f),
    "Hip Adduction Machine" to frontSeated(onBody(3f, -20f), onBody(3f, -20f), feet = 26f, endFeet = 6f),
    "Sumo Deadlift" to Figure(
        Pose(p(0f, 26f), hand = room(6f, 17f), foot = room(17f, 3f), toe = 120f, squash = 0.7f),
        Pose(p(0f, 40f), hand = room(5f, 38f), foot = room(17f, 3f), toe = 120f),
        Held.Barbell, front = true, elbow = p(0.3f, -1f), knee = p(1f, 0.2f)
    ),
    "Kettlebell Swing" to Figure(
        Pose(p(-13f, 33f), 62f, -30f, hand = onBody(-12f, -26.5f), foot = room(0f, 3f)),
        Pose(STAND, -4f, hand = onBody(28.8f, -2f), foot = room(0f, 3f)),
        Held.Kettlebell, thumb = 1f, elbow = p(-1f, -1f)
    ),
    "Donkey Kick" to Figure(QUAD, QUAD.copy(foot = room(-21f, 43f), toe = 300f, knee = p(-1f, -0.3f)), thumb = 1f, elbow = p(-0.5f, 1f)),
    "Fire Hydrant" to Figure(QUAD, QUAD.copy(foot = room(-12f, 16f), legReach = 0.75f, knee = p(-0.3f, -1f)), thumb = 1f, elbow = p(-0.5f, 1f)),
    "Reverse Hyperextension" to Figure(
        Pose(p(0f, 42f), 90f, -10f, hand = room(30f, 30f), foot = onBody(2f, -41f), toe = 180f),
        Pose(p(0f, 42f), 90f, -10f, hand = room(30f, 30f), foot = onBody(-41f, 2f), toe = 270f),
        thumb = 1f, elbow = p(0f, -1f), knee = p(1f, 0f),
        parts = { listOf(pad(-1f, 33.5f, 30f, 33.5f), strut(14f, 31f, 14f, 0.8f, 2.4f)) }
    )
)

private fun calves() = listOf(
    "Standing Calf Raise" to calfRaise(hand = onBody(4f, 6f), parts = { b ->
        listOf(pad(b.shoulder + p(-6f, 7f), b.shoulder + p(6f, 7f)), strut(b.shoulder + p(6f, 7f), p(b.shoulder.x + 6f, 0.8f)))
    }),
    "Smith Machine Calf Raise" to calfRaise(Held.Barbell, BACK_BAR, parts = { rails(-5f) }),
    "Barbell Calf Raise" to calfRaise(Held.Barbell, BACK_BAR),
    "Dumbbell Calf Raise" to calfRaise(Held.Dumbbell),
    "Bodyweight Calf Raise" to calfRaise(),
    "Single-Leg Calf Raise" to calfRaise(oneLeg = true),
    "Seated Calf Raise" to Figure(
        Pose(SEAT, hand = onBody(16f, -18f), foot = SEAT_FEET),
        Pose(SEAT, hand = onBody(16f, -16f), foot = room(19f, 8f), toe = 135f),
        thumb = 1f, elbow = p(-0.3f, -1f), parts = { b -> seat + pad(b.leg.joint + p(-4f, 6f), b.leg.joint + p(6f, 6f)) }
    ),
    "Seated Dumbbell Calf Raise" to Figure(
        Pose(SEAT, hand = onBody(16f, -18f), foot = SEAT_FEET),
        Pose(SEAT, hand = onBody(16f, -16f), foot = room(19f, 8f), toe = 135f),
        thumb = 1f, elbow = p(-0.3f, -1f), parts = { b -> seat + knob(b.leg.joint + p(0f, 7f), 3.9f, front = true) }
    ),
    "Donkey Calf Raise" to Figure(
        Pose(p(-4f, 44f), 85f, -20f, hand = room(32f, 34f)),
        Pose(p(-4f, 49f), 85f, -20f, hand = room(32f, 34f), foot = room(1f, 8f), toe = 135f),
        thumb = 1f, elbow = p(-0.5f, -1f), parts = { listOf(pad(26f, 31f, 38f, 31f), strut(32f, 29f, 32f, 0.8f)) }
    ),
    "Leg Press Calf Raise" to legPress(calves = true),
    "Pogo Hops" to Figure(
        Pose(hand = onBody(3f, -26f)),
        Pose(STAND + p(0f, 8f), hand = onBody(3f, -26f), foot = room(1f, 10f), toe = 125f),
        loop = 650
    )
)

// --- Core ---

private val SUPINE = Pose(p(0f, 6.5f), -90f, hand = HEAD_HOLD, foot = room(18f, 3f), knee = p(0.3f, 1f))

private fun crunch(end: Pose = SUPINE.copy(torso = -62f, head = 10f, hand = onBody(-4f, 7f)), start: Pose = SUPINE, parts: List<Part> = emptyList(), loop: Int = 0) =
    Figure(start, end, thumb = 1f, elbow = p(0.5f, 1f), loop = loop, parts = { parts })

private fun core() = listOf(
    "Cable Crunch" to Figure(
        Pose(p(0f, 23f), 15f, hand = onBody(5f, 6f), foot = room(-20f, 3f), toe = 260f, knee = p(0.2f, -1f)),
        Pose(p(-4f, 22f), 80f, 20f, hand = onBody(5f, 6f), foot = room(-20f, 3f), toe = 260f, knee = p(0.2f, -1f)),
        Held.Handle, thumb = 1f, elbow = p(1f, -0.5f), cable = p(22f, 100f)
    ),
    "Hanging Leg Raise" to legsUp(onBody(40f, 2f, way = 1)),
    "Hanging Knee Raise" to legsUp(onBody(21f, -20f)),
    "Toes-to-Bar" to legsUp(onBody(10f, 38f, way = 1), torso = -25f),
    "Dead Hang" to hanging(HANG_FROM_BAR, thumb = 0f),
    "Ab Wheel Rollout" to Figure(
        Pose(p(2f, 23f), 62f, -20f, hand = room(25f, 7f), foot = room(-20f, 3f), toe = 260f, knee = p(0.2f, -1f)),
        Pose(p(18.5f, 11.9f), 80f, -10f, hand = room(72f, 7f), foot = room(-20f, 3f), toe = 260f, knee = p(0.2f, -1f)),
        thumb = 1f, elbow = p(0f, 1f), parts = { b -> listOf(wheel(b.arm.end, 6.5f, 2.4f, Role.Gear), knob(b.arm.end, 1.6f)) }
    ),
    "Plank" to Figure(
        Pose(p(-3.5f, 13.6f), 80.7f, 8f, hand = onBody(14f, -15f), foot = room(-44f, 7f), toe = 160f),
        Pose(p(-3.5f, 14.4f), 80.7f, 8f, hand = onBody(14f, -15f), foot = room(-44f, 7f), toe = 160f),
        elbow = p(-1f, -1f)
    ),
    "Side Plank" to Figure(
        Pose(p(0f, 17f), 72f, hand = onBody(13.8f, -15f), farHand = onBody(0f, 28f), foot = room(-38f, 4f), farFoot = room(-39f, 9f), toe = 120f, farToe = 120f),
        Pose(p(0f, 17.8f), 72f, hand = onBody(13.8f, -15f), farHand = onBody(0f, 28f), foot = room(-38f, 4f), farFoot = room(-39f, 9f), toe = 120f, farToe = 120f),
        front = true, elbow = p(-0.2f, -1f)
    ),
    "Hollow Body Hold" to Figure(
        Pose(p(0f, 7f), -78f, 10f, hand = onBody(-27f, 5f), foot = room(40f, 14f), toe = 60f),
        Pose(p(0f, 7f), -76f, 10f, hand = onBody(-27f, 6f), foot = room(40f, 15f), toe = 60f),
        elbow = p(0f, 1f), knee = p(0f, 1f)
    ),
    "Decline Sit-Up" to Figure(
        Pose(p(0f, 25.5f), -105f, hand = HEAD_HOLD, foot = room(28f, 33f), knee = p(0.2f, 1f)),
        Pose(p(0f, 25.5f), -20f, hand = onBody(1f, 8f), foot = room(28f, 33f), knee = p(0.2f, 1f)),
        thumb = 1f, elbow = p(0.5f, 1f),
        parts = { listOf(pad(-34f, 10f, 26f, 26f), strut(-30f, 10f, -30f, 0.8f), strut(22f, 24f, 22f, 0.8f), knob(p(31f, 30f), 3f, Role.Frame)) }
    ),
    "Sit-Up" to crunch(SUPINE.copy(torso = -5f, hand = onBody(1f, 8f))),
    "Crunch" to crunch(),
    "Oblique Crunch" to crunch(),
    "Heel Touches" to crunch(SUPINE.copy(torso = -70f, head = 10f, hand = onBody(14f, -8f)), SUPINE.copy(torso = -70f, head = 10f, hand = onBody(10f, -4f))),
    "Stability Ball Crunch" to crunch(
        Pose(p(6f, 22f), -70f, 10f, hand = HEAD_HOLD, foot = room(28f, 3f), knee = p(0.3f, 1f)),
        Pose(p(6f, 22f), -100f, hand = HEAD_HOLD, foot = room(28f, 3f), knee = p(0.3f, 1f)),
        listOf(knob(p(0f, 13f), 13f, Role.Frame))
    ),
    "Bicycle Crunch" to crunch(
        SUPINE.copy(torso = -65f, hand = onBody(-4f, 7f), foot = room(38f, 12f), farFoot = room(14f, 18f), farKnee = p(0.3f, 1f)),
        SUPINE.copy(torso = -65f, hand = onBody(-4f, 7f), foot = room(14f, 18f), farFoot = room(38f, 12f), farKnee = p(0.3f, 1f)),
        loop = 1600
    ),
    "Reverse Crunch" to Figure(
        SUPINE.copy(hand = room(-8f, 2f), foot = room(18f, 16f)),
        SUPINE.copy(hip = p(2f, 9f), torso = -100f, hand = room(-8f, 2f), foot = room(6f, 30f), knee = p(0f, 1f)),
        thumb = 1f, elbow = p(0f, 1f)
    ),
    "Lying Leg Raise" to Figure(
        SUPINE.copy(hand = room(-6f, 2f), foot = onBody(41f, -1f), toe = 10f, knee = p(0f, 1f)),
        SUPINE.copy(hand = room(-6f, 2f), foot = onBody(4f, 40f), toe = 60f, knee = p(0f, 1f)),
        thumb = 1f, elbow = p(0f, 1f)
    ),
    "Flutter Kicks" to Figure(
        SUPINE.copy(torso = -85f, head = 8f, hand = room(-4f, 2f), foot = onBody(40f, 9f), farFoot = onBody(40f, 2f), toe = 20f, knee = p(0f, 1f)),
        SUPINE.copy(torso = -85f, head = 8f, hand = room(-4f, 2f), foot = onBody(40f, 2f), farFoot = onBody(40f, 9f), toe = 20f, knee = p(0f, 1f)),
        loop = 700, elbow = p(0f, 1f)
    ),
    "V-Up" to Figure(
        SUPINE.copy(hand = onBody(-28f, 1f), foot = onBody(41f, 0f), toe = 10f, knee = p(0f, 1f)),
        SUPINE.copy(hip = p(0f, 5f), torso = -35f, head = 10f, hand = onBody(22f, 10f), foot = onBody(18f, 37f), toe = 40f, knee = p(0f, 1f)),
        thumb = 1f, elbow = p(0f, 1f)
    ),
    "Dead Bug" to Figure(
        SUPINE.copy(hand = onBody(0f, 28.5f), farHand = onBody(0f, 28.5f), foot = onBody(20f, 21f), farFoot = onBody(20f, 21f), knee = p(-0.2f, 1f), farKnee = p(-0.2f, 1f)),
        SUPINE.copy(hand = onBody(-28f, 4f), farHand = onBody(0f, 28.5f), foot = onBody(20f, 21f), farFoot = onBody(41f, 3f), knee = p(-0.2f, 1f), farKnee = p(-0.2f, 1f)),
        thumb = 1f, elbow = p(0f, 1f)
    ),
    "L-Sit" to Figure(
        Pose(p(0f, 22f), 0f, hand = room(2f, 20f), foot = onBody(41f, 0f), toe = 20f),
        Pose(p(0f, 22.8f), 0f, hand = room(2f, 20f), foot = onBody(41f, 1f), toe = 20f),
        elbow = p(-1f, 0f), knee = p(0f, 1f),
        parts = { listOf(strut(-8f, 20f, 10f, 20f, 2.4f, Role.Gear, front = true), strut(-6f, 20f, -6f, 0.8f), strut(8f, 20f, 8f, 0.8f)) }
    ),
    "Dragon Flag" to Figure(
        Pose(p(5.5f, 45.9f), -150f, hand = room(-22f, 18f), foot = room(26f, 81.4f), toe = 30f),
        Pose(p(18.1f, 29.5f), -105f, hand = room(-22f, 18f), foot = room(57.7f, 40.1f), toe = 75f),
        thumb = 1f, elbow = p(0f, 1f), knee = p(-1f, 0f), parts = { bench(-30f, 20f) }
    ),
    "Bird Dog" to Figure(QUAD, QUAD.copy(hand = room(54f, 32f), farFoot = room(-40f, 25f), farToe = 200f, farKnee = p(0f, -1f)), thumb = 1f, elbow = p(-0.5f, 1f)),
    "Bear Crawl" to Figure(
        QUAD.copy(hip = p(0f, 27f), torso = 80f, hand = room(26f, 2f), farHand = room(32f, 2f), foot = room(-16f, 3f), farFoot = room(-8f, 3f), toe = 240f, farToe = 240f, knee = p(1f, -0.3f), farKnee = p(1f, -0.3f)),
        QUAD.copy(hip = p(0f, 27f), torso = 80f, hand = room(32f, 2f), farHand = room(26f, 2f), foot = room(-8f, 3f), farFoot = room(-16f, 3f), toe = 240f, farToe = 240f, knee = p(1f, -0.3f), farKnee = p(1f, -0.3f)),
        loop = 1300, elbow = p(-0.5f, 1f)
    ),
    "Mountain Climbers" to plankTop().let { top ->
        Figure(
            top.copy(foot = room(-40f, 7f), farFoot = room(-12f, 10f), farToe = 160f, knee = p(1f, -0.5f), farKnee = p(1f, -0.5f)),
            top.copy(foot = room(-12f, 10f), farFoot = room(-40f, 7f), farToe = 160f, knee = p(1f, -0.5f), farKnee = p(1f, -0.5f)),
            loop = 700, elbow = p(-0.6f, 1f)
        )
    },
    "Stability Ball Knee Tuck" to plankTop(p(-40f, 16f)).let { top ->
        val shoulder = top.hip + dir(top.torso) * 27f
        val hip = p(-2f, 30f)
        Figure(
            top, top.copy(hip = hip, torso = aim(hip, shoulder), foot = room(-16f, 18f)),
            thumb = 1f, elbow = p(-0.6f, 1f), knee = p(1f, -0.5f),
            parts = { b -> listOf(knob(b.leg.end + p(1f, -8.5f), 8f, Role.Frame)) }
        )
    },
    "Machine Crunch" to Figure(
        Pose(SEAT, 0f, hand = onBody(4f, 6f), foot = SEAT_FEET),
        Pose(SEAT, 38f, 15f, hand = onBody(4f, 6f), foot = SEAT_FEET),
        Held.Handle, thumb = 1f, elbow = p(1f, -0.5f), parts = { seatBack }
    ),
    "Pallof Press" to Figure(
        Pose(p(0f, 40f), hand = onBody(8f, -10f), foot = room(3f, 3f)),
        Pose(p(0f, 40f), hand = onBody(27f, -8f), foot = room(3f, 3f)),
        Held.Handle, thumb = 1f, elbow = p(-0.5f, -1f)
    ),
    "Russian Twist" to Figure(
        Pose(p(0f, 8f), hand = room(-14f, 30f), farHand = room(-14f, 30f), foot = room(9f, 9f), thigh = 0.35f, knee = p(1f, 0.6f)),
        Pose(p(0f, 8f), hand = room(14f, 30f), farHand = room(14f, 30f), foot = room(9f, 9f), thigh = 0.35f, knee = p(1f, 0.6f)),
        Held.Ball, oneHand = true, front = true, elbow = p(0f, -1f)
    ),
    "Woodchopper" to Figure(
        Pose(STAND, 6f, hand = room(24f, 90f), farHand = room(24f, 90f), foot = room(8f, 3f), farFoot = room(-8f, 3f)),
        Pose(p(0f, 40f), -8f, hand = room(-18f, 22f), farHand = room(-18f, 22f), foot = room(8f, 3f), farFoot = room(-8f, 3f)),
        Held.Handle, oneHand = true, front = true, thumb = 1f, elbow = p(0f, -1f), cable = p(40f, 100f)
    ),
    "Cable Lift" to Figure(
        Pose(p(0f, 40f), -8f, hand = room(-18f, 22f), farHand = room(-18f, 22f), foot = room(8f, 3f), farFoot = room(-8f, 3f)),
        Pose(STAND, 6f, hand = room(24f, 90f), farHand = room(24f, 90f), foot = room(8f, 3f), farFoot = room(-8f, 3f)),
        Held.Handle, oneHand = true, front = true, thumb = 1f, elbow = p(0f, -1f), cable = p(-40f, 8f)
    ),
    "Dumbbell Side Bend" to Figure(
        // Down towards the weight, then up and over past straight.
        Pose(STAND, -16f, hand = HANG, foot = room(6f, 3f), farFoot = room(-6f, 3f)),
        Pose(STAND, 12f, hand = HANG, foot = room(6f, 3f), farFoot = room(-6f, 3f)),
        Held.Dumbbell, oneHand = true, front = true, thumb = 1f
    ),
    "Landmine Rotation" to Figure(
        // The bar end swings in an arc over the head from side to side, not straight across the shoulders.
        Pose(hand = room(-22f, 64f), farHand = room(-22f, 64f), foot = room(8f, 3f)),
        Pose(hand = room(22f, 64f), farHand = room(22f, 64f), foot = room(8f, 3f)),
        front = true, elbow = p(0f, -1f),
        mid = Pose(hand = room(0f, 90f), farHand = room(0f, 90f), foot = room(8f, 3f)),
        parts = { b -> listOf(strut(p(0f, 1f), b.arm.end, 2.4f, Role.Gear, front = true), knob(b.arm.end, 6f, front = true)) }
    ),
    "Medicine Ball Slam" to Figure(
        Pose(STAND, -4f, hand = OVERHEAD),
        Pose(p(-10f, 38f), 60f, -30f, hand = onBody(4f, -27f)),
        Held.Ball, oneHand = true
    ),
    "Kettlebell Windmill" to Figure(
        Pose(p(3f, 43f), 0f, hand = onBody(0f, 28.5f), farHand = room(-9.5f, 42f), foot = room(12f, 3f), farFoot = room(-10f, 3f)),
        Pose(p(6f, 40f), -45f, hand = onBody(0f, 28.5f), farHand = room(-14f, 8f), foot = room(12f, 3f), farFoot = room(-10f, 3f)),
        Held.Kettlebell, front = true, thumb = 1f, elbow = p(1f, 0f)
    )
)

private fun forearms() = listOf(
    "Wrist Curl" to Figure(
        Pose(SEAT, 28f, hand = room(25f, 26f), foot = room(21f, 3f)),
        Pose(SEAT, 28f, hand = room(25f, 30f), foot = room(21f, 3f)),
        Held.Dumbbell, thumb = 1f, elbow = p(0f, -1f), parts = { seat }
    ),
    "Reverse Wrist Curl" to Figure(
        Pose(SEAT, 28f, hand = room(25f, 26f), foot = room(21f, 3f)),
        Pose(SEAT, 28f, hand = room(25f, 30f), foot = room(21f, 3f)),
        Held.Dumbbell, thumb = 1f, elbow = p(0f, -1f), parts = { seat }
    ),
    "Reverse Curl" to curl(Held.EzBar),
    "Behind-the-Back Wrist Curl" to Figure(
        Pose(hand = onBody(-4f, -28f)), Pose(hand = onBody(-4f, -24f)),
        Held.Barbell, thumb = 1f, elbow = p(-1f, 0f)
    ),
    "Farmer's Carry" to stride(9f, 3f, 2f, 43f, 1100, Held.Dumbbell, carry = true),
    "Suitcase Carry" to stride(9f, 3f, 2f, 43f, 1100, Held.Dumbbell, carry = true, oneHand = true),
    "Wrist Roller" to Figure(
        Pose(hand = onBody(27f, -4f)), Pose(hand = onBody(27f, -3f)),
        thumb = 1f, elbow = p(0f, -1f),
        parts = { b ->
            val top = b.arm.end
            val len = 32f - 20f * b.t
            listOf(strut(top + p(-4f, 0f), top + p(4f, 0f), 2.6f, Role.Gear, front = true), strut(top, top + p(0f, -len), 1.1f, Role.Cable), knob(top + p(0f, -len - 5f), 5f))
        }
    ),
    "Plate Pinch" to Figure(Pose(), held = Held.Plate, oneHand = true)
)

// --- Cardio ---

/** Walking or running: one stride with the legs and arms swapped at the other end of the loop. */
private fun stride(
    len: Float, lift: Float, lean: Float, hipY: Float, period: Int, held: Held? = null,
    carry: Boolean = false, oneHand: Boolean = false, parts: (Body) -> List<Part> = { emptyList() }
): Figure {
    val back = if (carry) HANG else onBody(-10f, -15f)
    val fore = if (carry) HANG else onBody(10f, -13f)
    val a = Pose(p(0f, hipY), lean, hand = back, farHand = fore, foot = room(len, 3f), farFoot = room(-len, 3f + lift), toe = 80f, farToe = 140f)
    val b = a.copy(hand = fore, farHand = back, foot = room(-len, 3f + lift), farFoot = room(len, 3f), toe = 140f, farToe = 80f)
    return Figure(a, b, held, oneHand, thumb = 0f, elbow = p(-1f, -0.3f), loop = period, parts = parts)
}

private fun bike(stationary: Boolean, armHandles: Boolean = false) = Figure(
    Pose(p(0f, 40f), 55f, -35f, hand = room(34f, 42f), farHand = if (armHandles) room(28f, 46f) else null, foot = crank(14f, 14f, 8f), farFoot = crank(14f, 14f, 8f, 180f), toe = 100f, farToe = 100f),
    Pose(p(0f, 40f), 55f, -35f, hand = room(if (armHandles) 28f else 34f, if (armHandles) 46f else 42f), farHand = if (armHandles) room(34f, 42f) else null, foot = crank(14f, 14f, 8f), farFoot = crank(14f, 14f, 8f, 180f), toe = 100f, farToe = 100f),
    loop = 1100, elbow = p(-0.3f, -1f),
    parts = { b ->
        val frame = listOf(strut(14f, 14f, 2f, 33f, 2.4f), strut(2f, 33f, 32f, 38f, 2.4f), strut(14f, 14f, 32f, 38f, 2.4f), pad(-5f, 34.5f, 6f, 34.5f), strut(32f, 38f, 34f, 44f, 2.4f))
        val wheels = if (stationary) listOf(strut(-16f, 1f, 40f, 1f, 3f), strut(14f, 14f, 14f, 1f, 2.4f), strut(32f, 38f, 38f, 1f, 2.4f))
        else listOf(wheel(p(-18f, 12f), 12f), wheel(p(46f, 12f), 12f), strut(-18f, 12f, 14f, 14f, 2.4f), strut(32f, 38f, 46f, 12f, 2.4f))
        val handles = if (armHandles) listOf(strut(b.arm.end, p(30f, 16f), 2f, Role.Gear), strut(b.farArm.end, p(30f, 16f), 2f, Role.Gear)) else emptyList()
        frame + wheels + handles + listOf(knob(b.leg.end, 1.8f), knob(b.farLeg.end, 1.8f))
    }
)

private fun cardio() = listOf(
    "Running" to stride(14f, 10f, 10f, 41f, 800),
    "Sprints" to stride(18f, 14f, 18f, 40f, 600),
    "Walking" to stride(10f, 2f, 2f, 43f, 1200),
    "Hiking" to stride(10f, 3f, 8f, 43f, 1300, parts = { b -> listOf(knob(b.shoulder - b.up * 8f + p(-8f, 0f), 7f, Role.Frame)) }),
    "Treadmill" to stride(14f, 10f, 10f, 41f, 800, parts = { listOf(pad(-34f, 1.5f, 34f, 1.5f), strut(32f, 2f, 38f, 62f, 2.4f), strut(38f, 62f, 26f, 60f, 2.4f)) }),
    "High Knees" to Figure(
        Pose(hand = onBody(-10f, -15f), farHand = onBody(10f, -13f), foot = room(6f, 24f), farFoot = room(-1f, 3f), toe = 140f, farToe = 90f),
        Pose(hand = onBody(10f, -13f), farHand = onBody(-10f, -15f), foot = room(1f, 3f), farFoot = room(6f, 24f), toe = 90f, farToe = 140f),
        loop = 650, elbow = p(-1f, -0.3f)
    ),
    "Jumping Jacks" to Figure(
        Pose(hand = onBody(2f, -28f), foot = room(6f, 3f)),
        Pose(p(0f, 45f), hand = onBody(9f, 27f), foot = room(16f, 3f)),
        front = true, loop = 900, elbow = p(1f, 0f)
    ),
    "Burpee" to plankTop().let { top ->
        Figure(
            top.copy(hand = onBody(1.4f, -28.5f)),
            Pose(p(0f, 47f), 0f, hand = onBody(1f, 28.5f), foot = room(1f, 7f), toe = 120f),
            thumb = 1f, elbow = p(-0.6f, 1f)
        )
    },
    "Jump Rope" to Figure(
        Pose(hand = onBody(9f, -20f), foot = room(1f, 3f), toe = 110f),
        Pose(STAND + p(0f, 4f), hand = onBody(9f, -20f), foot = room(1f, 7f), toe = 130f),
        loop = 600, elbow = p(-1f, -0.3f),
        parts = { b ->
            val rope = b.hip + p(0f, 8f) + dir(b.time * 360f) * 50f
            listOf(strut(b.arm.end, rope, 1.1f, Role.Cable), strut(b.farArm.end, rope, 1.1f, Role.Cable))
        }
    ),
    "Battle Ropes" to Figure(
        Pose(p(-6f, 35f), 20f, hand = onBody(22f, -4f), farHand = onBody(22f, -20f), foot = room(4f, 3f)),
        Pose(p(-6f, 35f), 20f, hand = onBody(22f, -20f), farHand = onBody(22f, -4f), foot = room(4f, 3f)),
        loop = 700, elbow = p(-0.3f, -1f),
        parts = { b -> listOf(strut(b.arm.end, p(72f, 3f), 1.8f, Role.Gear), strut(b.farArm.end, p(72f, 3f), 1.8f, Role.Gear), knob(p(72f, 3f), 2.5f, Role.Frame)) }
    ),
    "Swimming" to Figure(
        Pose(p(0f, 40f), 90f, 10f, hand = crank(0f, 0f, 27f, 90f, onBody = true), farHand = crank(0f, 0f, 27f, 270f, onBody = true), foot = room(-41f, 42f), farFoot = room(-41f, 38f), toe = 270f),
        Pose(p(0f, 40f), 90f, 10f, hand = crank(0f, 0f, 27f, 90f, onBody = true), farHand = crank(0f, 0f, 27f, 270f, onBody = true), foot = room(-41f, 38f), farFoot = room(-41f, 42f), toe = 270f),
        floor = false, loop = 1600, elbow = p(0f, 1f), parts = { listOf(strut(-55f, 47f, 45f, 47f, 1.1f, Role.Cable)) }
    ),
    "Cycling" to bike(stationary = false),
    "Stationary Bike" to bike(stationary = true),
    "Assault Bike" to bike(stationary = true, armHandles = true),
    "Recumbent Bike" to Figure(
        Pose(p(0f, 24f), -25f, hand = room(-4f, 18f), foot = crank(32f, 22f, 8f), farFoot = crank(32f, 22f, 8f, 180f), toe = 60f, farToe = 60f, knee = p(0f, 1f), farKnee = p(0f, 1f)),
        loop = 1100, elbow = p(0f, -1f),
        parts = { b -> listOf(backPad(p(0f, 24f), -25f), pad(-6f, 15.5f, 8f, 15.5f), strut(-10f, 1f, 36f, 1f, 3f), strut(32f, 22f, 32f, 1f, 2.4f), strut(0f, 14f, 0f, 1f, 2.4f), knob(b.leg.end, 1.8f), knob(b.farLeg.end, 1.8f)) }
    ),
    "Elliptical" to Figure(
        Pose(p(0f, 48f), 4f, hand = room(16f, 62f), farHand = room(10f, 58f), foot = crank(2f, 14f, 7f), farFoot = crank(2f, 14f, 7f, 180f)),
        Pose(p(0f, 48f), 4f, hand = room(10f, 58f), farHand = room(16f, 62f), foot = crank(2f, 14f, 7f), farFoot = crank(2f, 14f, 7f, 180f)),
        loop = 1300, elbow = p(-1f, -0.3f),
        parts = { b -> listOf(strut(-25f, 2f, 32f, 2f, 3f), strut(30f, 2f, 26f, 70f, 2.4f), strut(b.arm.end, p(20f, 12f), 2f, Role.Gear), strut(b.farArm.end, p(20f, 12f), 2f, Role.Frame)) }
    ),
    "Stair Climber" to Figure(
        Pose(p(0f, 44f), 6f, hand = room(18f, 56f), foot = room(6f, 16f), farFoot = room(2f, 4f)),
        Pose(p(0f, 44f), 6f, hand = room(18f, 56f), foot = room(2f, 4f), farFoot = room(6f, 16f)),
        loop = 1100, elbow = p(-0.5f, -1f),
        parts = { listOf(pad(-4f, 2f, 12f, 2f), pad(0f, 14f, 16f, 14f), strut(20f, 0.8f, 20f, 58f, 2.4f)) }
    ),
    "Rowing Machine" to Figure(
        Pose(p(8f, 12f), 28f, -10f, hand = onBody(26f, -6f), foot = room(30f, 8f), toe = 30f, knee = p(0f, 1f)),
        Pose(p(-10f, 12f), -22f, hand = onBody(6f, -13f), foot = room(30f, 8f), toe = 30f, knee = p(0f, 1f)),
        Held.Handle, loop = 2000, elbow = p(-1f, -0.3f),
        parts = { b -> listOf(pad(-44f, 2.5f, 40f, 2.5f), pad(b.hip + p(-7f, -7f), b.hip + p(5f, -7f)), wheel(p(50f, 14f), 8f), strut(p(50f, 14f), b.arm.end, 1.1f, Role.Cable), strut(34f, 2f, 34f, 14f, 2.4f)) }
    ),
    "Ski Erg" to Figure(
        Pose(hand = onBody(8f, 27f), foot = room(2f, 3f)),
        Pose(p(-9f, 38f), 50f, -30f, hand = onBody(4f, -26f), foot = room(2f, 3f)),
        Held.Handle, loop = 1600, elbow = p(0f, -1f), cable = p(22f, 104f)
    )
)
