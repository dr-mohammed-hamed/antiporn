package com.drmohammed.antiporn.ai.core.tracking

import kotlin.math.abs

/**
 * معالج حل معادلات Lucas-Kanade التفاضلية التكرارية للتدفق البصري.
 */
internal class KltSolver(
    private val windowRadius: Int,
    private val maxIterations: Int,
    private val minDeterminant: Float,
    private val maxResidual: Float,
    private val convergenceEpsilon: Float,
    private val numPyramidLevels: Int
) {
    private val scratchL1Delta = FloatArray(2)

    /**
     * تتبع نقطة محددة عبر هرم Lucas-Kanade ثنائي الطبقات (Level 1 Coarse -> Level 0 Fine).
     */
    fun trackPointPyramidal(
        prevLuma: ByteArray,
        currLuma: ByteArray,
        prevLumaL1: ByteArray,
        currLumaL1: ByteArray,
        ptX: Float,
        ptY: Float,
        width: Int,
        height: Int,
        outDelta: FloatArray
    ): Boolean {
        var initVx = 0f
        var initVy = 0f

        if (numPyramidLevels >= 2) {
            val w1 = width shr 1
            val h1 = height shr 1
            val ptX1 = ptX * 0.5f
            val ptY1 = ptY * 0.5f

            val l1Success = trackPointLK(
                prevImg = prevLumaL1,
                currImg = currLumaL1,
                imgWidth = w1,
                imgHeight = h1,
                ptX = ptX1,
                ptY = ptY1,
                initVx = 0f,
                initVy = 0f,
                outDelta = scratchL1Delta
            )

            if (l1Success) {
                initVx = scratchL1Delta[0] * 2.0f
                initVy = scratchL1Delta[1] * 2.0f
            }
        }

        return trackPointLK(
            prevImg = prevLuma,
            currImg = currLuma,
            imgWidth = width,
            imgHeight = height,
            ptX = ptX,
            ptY = ptY,
            initVx = initVx,
            initVy = initVy,
            outDelta = outDelta
        )
    }

    /**
     * تنفيذ خوارزمية Lucas-Kanade التكرارية على طبقة صورة واحدة بنعومة ودقة متناهية.
     */
    fun trackPointLK(
        prevImg: ByteArray,
        currImg: ByteArray,
        imgWidth: Int,
        imgHeight: Int,
        ptX: Float,
        ptY: Float,
        initVx: Float,
        initVy: Float,
        outDelta: FloatArray
    ): Boolean {
        if (ptX.isNaN() || ptY.isNaN() || initVx.isNaN() || initVy.isNaN()) {
            return false
        }

        val r = windowRadius
        val minBorder = (r + 1).toFloat()
        val maxBorderX = (imgWidth - r - 2).toFloat()
        val maxBorderY = (imgHeight - r - 2).toFloat()

        if (ptX < minBorder || ptX > maxBorderX || ptY < minBorder || ptY > maxBorderY) {
            return false
        }

        // تقريب إحداثيات النقطة في الإطار المرجعي السابق لسرعة حساب مصفوفة التدرج G
        val ptXi = ptX.toInt()
        val ptYi = ptY.toInt()

        // 1. حساب مصفوفة التدرج المكاني G = [Gxx, Gxy; Gxy, Gyy]
        var gxx = 0f
        var gxy = 0f
        var gyy = 0f

        for (dy in -r..r) {
            val y = ptYi + dy
            val rowOffset = y * imgWidth
            for (dx in -r..r) {
                val x = ptXi + dx
                val idx = rowOffset + x

                // التدرج المركزي (Central Difference): Ix = (I(x+1) - I(x-1)) / 2
                val ix = ((prevImg[idx + 1].toInt() and 0xFF) - (prevImg[idx - 1].toInt() and 0xFF)) * 0.5f
                val iy = ((prevImg[idx + imgWidth].toInt() and 0xFF) - (prevImg[idx - imgWidth].toInt() and 0xFF)) * 0.5f

                gxx += ix * ix
                gxy += ix * iy
                gyy += iy * iy
            }
        }

        // محدد المصفوفة (Determinant)
        val det = gxx * gyy - gxy * gxy
        if (det.isNaN() || det <= 0.0001f || det < minDeterminant) {
            // منطقة ملساء بدون تفاصيل (Textureless) أو معضلة الفتحة (Aperture problem)
            return false
        }
        val invDet = 1.0f / det

        // 2. التكرار التخميني لـ Lucas-Kanade (Iterative Optical Flow)
        var vx = initVx
        var vy = initVy
        var iter = 0

        while (iter < maxIterations) {
            val curX = ptX + vx
            val curY = ptY + vy

            if (curX.isNaN() || curY.isNaN() || curX < minBorder || curX > maxBorderX || curY < minBorder || curY > maxBorderY) {
                return false
            }

            var bx = 0f
            var by = 0f

            for (dy in -r..r) {
                val y = ptYi + dy
                val rowOffset = y * imgWidth
                val sampleY = curY + dy

                for (dx in -r..r) {
                    val x = ptXi + dx
                    val idx = rowOffset + x
                    val sampleX = curX + dx

                    val ix = ((prevImg[idx + 1].toInt() and 0xFF) - (prevImg[idx - 1].toInt() and 0xFF)) * 0.5f
                    val iy = ((prevImg[idx + imgWidth].toInt() and 0xFF) - (prevImg[idx - imgWidth].toInt() and 0xFF)) * 0.5f

                    val prevVal = (prevImg[idx].toInt() and 0xFF).toFloat()
                    val currVal = KltPyramidHelper.sampleBilinear(currImg, imgWidth, imgHeight, sampleX, sampleY)

                    val it = prevVal - currVal
                    bx += ix * it
                    by += iy * it
                }
            }

            // خطوة التحديث عبر مقلوب مصفوفة G: DeltaV = G^-1 * b
            val deltaVx = (gyy * bx - gxy * by) * invDet
            val deltaVy = (-gxy * bx + gxx * by) * invDet

            if (deltaVx.isNaN() || deltaVx.isInfinite() || deltaVy.isNaN() || deltaVy.isInfinite()) {
                return false
            }

            vx += deltaVx
            vy += deltaVy

            if (vx.isNaN() || vx.isInfinite() || vy.isNaN() || vy.isInfinite()) {
                return false
            }

            if ((deltaVx * deltaVx + deltaVy * deltaVy) < convergenceEpsilon) {
                break
            }
            iter++
        }

        // 3. التحقق من الخطأ الضوئي المتبقي (Photometric Residual Check)
        val finalX = ptX + vx
        val finalY = ptY + vy
        if (finalX.isNaN() || finalY.isNaN() || finalX < minBorder || finalX > maxBorderX || finalY < minBorder || finalY > maxBorderY) {
            return false
        }

        var totalResidual = 0f
        val windowArea = (2 * r + 1) * (2 * r + 1)

        for (dy in -r..r) {
            val y = ptYi + dy
            val rowOffset = y * imgWidth
            val sampleY = finalY + dy

            for (dx in -r..r) {
                val x = ptXi + dx
                val idx = rowOffset + x
                val sampleX = finalX + dx

                val prevVal = (prevImg[idx].toInt() and 0xFF).toFloat()
                val currVal = KltPyramidHelper.sampleBilinear(currImg, imgWidth, imgHeight, sampleX, sampleY)
                totalResidual += abs(prevVal - currVal)
            }
        }

        val meanResidual = totalResidual / windowArea.toFloat()
        if (meanResidual.isNaN() || meanResidual > maxResidual) {
            return false
        }

        outDelta[0] = vx
        outDelta[1] = vy
        return true
    }
}
