package com.hrips.browser

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Единый прогресс сворачивания нижней панели: 0 развёрнута, 1 свёрнута.
 *
 * Пока палец на панели, значение ведёт сам жест ([drag]), поэтому панель сжимается и растёт строго
 * пропорционально движению. После отпускания [settle] доводит значение до 0 или 1 пружиной, которой
 * передана скорость пальца. Остальное (страница, уведомления, панель) читает [value] внутри
 * layout/graphicsLayer-лямбд, поэтому за кадр пересчитывается только измерение и рисование.
 */
@Stable
internal class BarCollapse {
    private val anim = Animatable(0f)
    private var dragT by mutableFloatStateOf(0f)
    private var dragging by mutableStateOf(false)

    val value: Float get() = if (dragging) dragT else anim.value

    /** Палец лёг на панель: подхватываем текущее значение, даже если анимация ещё шла. */
    fun grab() {
        dragT = anim.value
        dragging = true
    }

    fun drag(t: Float) {
        dragT = t.coerceIn(0f, 1f)
        dragging = true
    }

    /** Доводит панель до [target]. [velocity] в долях хода в секунду (положительная: к свёрнутому). */
    suspend fun settle(target: Float, velocity: Float = 0f) {
        if (dragging) {
            anim.snapTo(dragT)
            dragging = false
        }
        anim.animateTo(target, HripsMotion.barLiquid(), initialVelocity = velocity)
    }

    /** Состояние изменилось не жестом (прокрутка страницы, нажатие): плавно идём к нему. */
    suspend fun follow(target: Float) {
        if (dragging || anim.targetValue == target) return
        anim.animateTo(target, HripsMotion.barLiquid())
    }
}
