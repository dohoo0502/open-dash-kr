package com.example.opendash.ui.screens

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class RoutePoint(
    val lng: Double,
    val lat: Double,
)

class RouteOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val routePoints = mutableListOf<RoutePoint>()

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00D5FF.toInt()
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val routeShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA001018.toInt()
        strokeWidth = 24f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }

    private val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.FILL
    }

    fun setRoute(points: List<RoutePoint>) {
        routePoints.clear()
        routePoints.addAll(points)
        invalidate()
    }

    fun clearRoute() {
        routePoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (routePoints.size < 2) {
            return
        }

        val bounds = calculateBounds(routePoints)
        val screenPoints = routePoints.map { point ->
            mapToScreen(point, bounds)
        }

        val path = Path()
        screenPoints.forEachIndexed { index, point ->
            if (index == 0) {
                path.moveTo(point.x, point.y)
            } else {
                path.lineTo(point.x, point.y)
            }
        }

        canvas.drawPath(path, routeShadowPaint)
        canvas.drawPath(path, routePaint)

        val start = screenPoints.first()
        val goal = screenPoints.last()

        canvas.drawCircle(start.x, start.y, 16f, startPaint)
        canvas.drawCircle(goal.x, goal.y, 20f, goalPaint)
    }

    private data class RouteBounds(
        val minLng: Double,
        val maxLng: Double,
        val minLat: Double,
        val maxLat: Double,
    )

    private fun calculateBounds(points: List<RoutePoint>): RouteBounds {
        var minLng = Double.MAX_VALUE
        var maxLng = -Double.MAX_VALUE
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE

        points.forEach { point ->
            minLng = min(minLng, point.lng)
            maxLng = max(maxLng, point.lng)
            minLat = min(minLat, point.lat)
            maxLat = max(maxLat, point.lat)
        }

        if (minLng == maxLng) {
            minLng -= 0.001
            maxLng += 0.001
        }

        if (minLat == maxLat) {
            minLat -= 0.001
            maxLat += 0.001
        }

        return RouteBounds(
            minLng = minLng,
            maxLng = maxLng,
            minLat = minLat,
            maxLat = maxLat,
        )
    }

    private fun mapToScreen(point: RoutePoint, bounds: RouteBounds): PointF {
        val horizontalPadding = width * 0.16f
        val verticalPadding = height * 0.22f

        val drawWidth = width - horizontalPadding * 2f
        val drawHeight = height - verticalPadding * 2f

        val lngRatio = ((point.lng - bounds.minLng) / (bounds.maxLng - bounds.minLng)).toFloat()
        val latRatio = ((point.lat - bounds.minLat) / (bounds.maxLat - bounds.minLat)).toFloat()

        val x = horizontalPadding + drawWidth * lngRatio
        val y = verticalPadding + drawHeight * (1f - latRatio)

        return PointF(x, y)
    }
}