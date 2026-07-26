package com.kickercam.ui

/**
 * Where the camera image lands inside the viewfinder, and how big.
 *
 * Coordinates are pixels in the viewfinder's own space. Whatever the image does not cover is left
 * black — there is no crop and no stretch, ever: [scale] is a single number applied to both axes, so
 * the picture cannot be distorted no matter what the buffer, the view and the rotation are.
 */
data class PreviewFit(
    val scale: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** True when the rotation swaps the image's width and height. */
fun isQuarterTurn(rotationDegrees: Int): Boolean {
    val normalised = normaliseRotation(rotationDegrees)
    return normalised == 90 || normalised == 270
}

fun normaliseRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

/**
 * Fits a [bufferWidth] x [bufferHeight] camera buffer, rotated clockwise by [rotationDegrees], inside
 * a [viewWidth] x [viewHeight] viewfinder, centred and preserving its shape.
 *
 * Returns null when any dimension is degenerate, which happens for a frame or two before the first
 * layout and before the camera has reported a format.
 */
fun previewFit(
    viewWidth: Float,
    viewHeight: Float,
    bufferWidth: Int,
    bufferHeight: Int,
    rotationDegrees: Int,
): PreviewFit? {
    if (viewWidth <= 0f || viewHeight <= 0f || bufferWidth <= 0 || bufferHeight <= 0) return null

    // The rotation decides which way round the image is once it is on screen; a quarter turn makes a
    // 1920x1080 buffer occupy a 1080x1920 shape.
    val quarterTurn = isQuarterTurn(rotationDegrees)
    val displayedWidth = if (quarterTurn) bufferHeight.toFloat() else bufferWidth.toFloat()
    val displayedHeight = if (quarterTurn) bufferWidth.toFloat() else bufferHeight.toFloat()

    // One scale for both axes. This single `min` is what guarantees black bars instead of stretching.
    val scale = minOf(viewWidth / displayedWidth, viewHeight / displayedHeight)

    val width = displayedWidth * scale
    val height = displayedHeight * scale
    return PreviewFit(
        scale = scale,
        left = (viewWidth - width) / 2f,
        top = (viewHeight - height) / 2f,
        width = width,
        height = height,
    )
}
