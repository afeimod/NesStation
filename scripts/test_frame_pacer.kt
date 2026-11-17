package com.nesstation.app.core.engine

fun main() {
    val clock = FrameDeadline()
    val period = 16_666_667L
    val start = 1_000_000_000L
    var now = start
    var deadline = clock.next(now, period)
    check(deadline == start + period)
    // Model a TV kernel waking 3 ms late every time: absolute time must
    // continue at 60 Hz instead of accumulating 3 ms per frame (~50 fps).
    repeat(600) {
        now = deadline + 3_000_000L
        deadline = clock.next(now, period)
    }
    check(deadline == start + period * 601)
    now = deadline + 5_000_000_000L
    check(clock.next(now, period) == now + period) // resume after pause
    check(clock.next(now, period / 2) == now + period / 2) // fast-forward
    val pal = 20_000_000L
    check(clock.next(now, pal) == now + pal)
    val waiter = Thread {
        check(!FramePacer.paceTo(System.nanoTime(), 5_000_000_000L))
    }
    waiter.start()
    waiter.interrupt()
    waiter.join(1000)
    check(!waiter.isAlive) // park/sleep interruption must terminate the loop
    println("PASS: accumulated oversleep, pause, speed change, PAL, interruption")
}
