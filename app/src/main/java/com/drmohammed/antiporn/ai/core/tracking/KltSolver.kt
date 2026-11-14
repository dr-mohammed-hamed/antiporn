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
    private val scratchLevelDelta = FloatArray(2)

    /**
     * تتبع نقطة محددة عبر هرم Lucas-Kanade متعدد الطبقات (حتى 5 طبقات L4 -> L3 -> L2 -> L1 -> L0)
     * للتعامل بكفاءة تامة مع قفزات التمرير السريعة (حتى 80 إلى 150 بكسل لكل إطار).
     */
    fun trackPointPyramidalMulti(
        prevPyramid: Array<ByteArray>,
        currPyramid: Array<ByteArray>,
        ptX: Float,
        ptY: Float,
        width: Int,
        height: Int,
        outDelta: FloatArray,
        activeLevels: Int = minOf(numPyramidLevels, prevPyramid.size)
    ): Boolean {
        var vx = 0f
        var vy = 0f
        val levels = activeLevels.coerceIn(1, 5)

        for (lvl in (levels - 1) downTo 1) {
            val scale = 1f / (1 shl lvl)
            val lvlW = width shr lvl
            val lvlH = height shr lvl
            val coarseRadius = when {
                lvl >= 4 -> 2 // في المستوى الرابع والأعلى (26x57)، نصف قطر 2 يضمن عدم رفض نقاط الأطراف
                lvl == 3 -> maxOf(windowRadius, 3)
                else -> windowRadius
            }
            val coarseIter = if (lvl >= 3) maxOf(maxIterations, 8) else maxIterations
            val coarseRes = if (lvl >= 3) maxResidual * 1.5f else maxResidual

            if (lvlW < (coarseRadius * 2 + 4) || lvlH < (coarseRadius * 2 + 4)) continue

            val px = ptX * scale
            val py = ptY * scale

            val success = trackPointLK(
                prevImg = prevPyramid[lvl],
                currImg = currPyramid[lvl],
                imgWidth = lvlW,
                imgHeight = lvlH,
                ptX = px,
                ptY = py,
                initVx = vx,
                initVy = vy,
                outDelta = scratchLevelDelta,
                customRadius = coarseRadius,
                customMaxIter = coarseIter,
                customMaxResidual = coarseRes
            )

            if (success) {
                vx = scratchLevelDelta[0] * 2.0f
                vy = scratchLevelDelta[1] * 2.0f
            }
            // في حال فشل طبقة معينة، لا نصفر vx/vy بل نحتفظ بآخر تقدير متاح أو ننتقل للمستوى الأدنى
        }

        return trackPointLK(
            prevImg = prevPyramid[0],
            currImg = currPyramid[0],
            imgWidth = width,
            imgHeight = height,
            ptX = ptX,
            ptY = ptY,
            initVx = vx,
            initVy = vy,
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
        outDelta: FloatArray,
        customRadius: Int = windowRadius,
        customMaxIter: Int = maxIterations,
        customMaxResidual: Float = maxResidual
    ): Boolean {
        if (ptX.isNaN() || ptY.isNaN() || initVx.isNaN() || initVy.isNaN()) {
            return false
        }

        val r = customRadius
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

        while (iter < customMaxIter) {
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
        if (meanResidual.isNaN() || meanResidual > customMaxResidual) {
            return false
        }

        outDelta[0] = vx
        outDelta[1] = vy
        return true
    }
}
