package com.photonx.camera.engine

import com.photonx.camera.data.AppLogger
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class AlignedBurstResult(
    val alignedMats: List<Mat>,
    val rejectedCount: Int
)

@Singleton
class FrameAligner @Inject constructor(
    private val logger: AppLogger
) {

    fun alignFrames(framesBgr: List<Mat>, refIndex: Int): AlignedBurstResult {
        if (framesBgr.size <= 1) {
            return AlignedBurstResult(framesBgr.map { it.clone() }, 0)
        }

        val refFull = framesBgr[refIndex]
        val fullW = refFull.cols()
        val fullH = refFull.rows()

        val alignWidth = 640.0
        val scale = alignWidth / maxOf(fullW, fullH).toDouble()
        val smallSize = Size(
            (fullW * scale).coerceAtLeast(64.0),
            (fullH * scale).coerceAtLeast(64.0)
        )

        val refGraySmall = toNormalizedGraySmall(refFull, smallSize)
        val alignedList = mutableListOf<Mat>()
        var rejected = 0

        for (idx in framesBgr.indices) {
            if (idx == refIndex) {
                alignedList.add(refFull.clone())
                continue
            }

            val targetFull = framesBgr[idx]
            val targetGraySmall = toNormalizedGraySmall(targetFull, smallSize)

            val warpedFull = tryAlignSingleFrame(
                refGraySmall = refGraySmall,
                targetGraySmall = targetGraySmall,
                targetFull = targetFull,
                scaleToFull = 1.0 / scale
            )

            targetGraySmall.release()

            if (warpedFull != null) {
                alignedList.add(warpedFull)
            } else {
                rejected++
                logger.warn("Aligner", "Rejected frame $idx due to excessive motion/alignment error")
            }
        }

        refGraySmall.release()
        return AlignedBurstResult(alignedList, rejected)
    }

    private fun toNormalizedGraySmall(bgr: Mat, targetSize: Size): Mat {
        val resized = Mat()
        Imgproc.resize(bgr, resized, targetSize, 0.0, 0.0, Imgproc.INTER_AREA)
        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)
        resized.release()
        val eq = Mat()
        Imgproc.equalizeHist(gray, eq)
        gray.release()
        return eq
    }

    private fun tryAlignSingleFrame(
        refGraySmall: Mat,
        targetGraySmall: Mat,
        targetFull: Mat,
        scaleToFull: Double
    ): Mat? {
        val eccMatrix = Mat.eye(2, 3, CvType.CV_32F)
        try {
            val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 45, 1e-4)
            val cc = Video.findTransformECC(
                refGraySmall,
                targetGraySmall,
                eccMatrix,
                Video.MOTION_EUCLIDEAN,
                criteria
            )

            if (cc > 0.55) {
                val tx = eccMatrix.get(0, 2)[0] * scaleToFull
                val ty = eccMatrix.get(1, 2)[0] * scaleToFull

                if (abs(tx) > targetFull.cols() * 0.12 || abs(ty) > targetFull.rows() * 0.12) {
                    eccMatrix.release()
                    return null
                }

                eccMatrix.put(0, 2, tx)
                eccMatrix.put(1, 2, ty)

                val warped = Mat()
                Imgproc.warpAffine(
                    targetFull,
                    warped,
                    eccMatrix,
                    targetFull.size(),
                    Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP,
                    Core.BORDER_REPLICATE
                )
                eccMatrix.release()
                return warped
            }
        } catch (_: Exception) {
        }
        eccMatrix.release()

        return tryOrbHomography(refGraySmall, targetGraySmall, targetFull, scaleToFull)
    }

    private fun tryOrbHomography(
        refGraySmall: Mat,
        targetGraySmall: Mat,
        targetFull: Mat,
        scaleToFull: Double
    ): Mat? {
        val orb = ORB.create(600)
        val kpRef = MatOfKeyPoint()
        val kpTgt = MatOfKeyPoint()
        val descRef = Mat()
        val descTgt = Mat()

        return try {
            orb.detectAndCompute(refGraySmall, Mat(), kpRef, descRef)
            orb.detectAndCompute(targetGraySmall, Mat(), kpTgt, descTgt)

            if (descRef.empty() || descTgt.empty() || kpRef.rows() < 12 || kpTgt.rows() < 12) {
                return null
            }

            val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
            val matches = MatOfDMatch()
            matcher.match(descTgt, descRef, matches)

            val matchList = matches.toList().sortedBy { it.distance }
            val goodMatches = matchList.take((matchList.size * 0.35).toInt().coerceAtLeast(10))
            if (goodMatches.size < 10) return null

            val refPtsList = kpRef.toList()
            val tgtPtsList = kpTgt.toList()

            val srcPts = MatOfPoint2f(*goodMatches.map { tgtPtsList[it.queryIdx].pt }.toTypedArray())
            val dstPts = MatOfPoint2f(*goodMatches.map { refPtsList[it.trainIdx].pt }.toTypedArray())

            val inlierMask = Mat()
            val hSmall = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 4.0, inlierMask)

            val inliers = Core.countNonZero(inlierMask)
            inlierMask.release()
            srcPts.release()
            dstPts.release()

            if (hSmall.empty() || inliers < 8) {
                hSmall.release()
                return null
            }

            val sMat = Mat.eye(3, 3, CvType.CV_64F).apply {
                put(0, 0, scaleToFull)
                put(1, 1, scaleToFull)
            }
            val sInv = Mat.eye(3, 3, CvType.CV_64F).apply {
                put(0, 0, 1.0 / scaleToFull)
                put(1, 1, 1.0 / scaleToFull)
            }
            val temp = Mat()
            val hFull = Mat()
            Core.gemm(sMat, hSmall, 1.0, Mat(), 0.0, temp)
            Core.gemm(temp, sInv, 1.0, Mat(), 0.0, hFull)

            sMat.release()
            sInv.release()
            temp.release()
            hSmall.release()

            val warped = Mat()
            Imgproc.warpPerspective(
                targetFull,
                warped,
                hFull,
                targetFull.size(),
                Imgproc.INTER_LINEAR,
                Core.BORDER_REPLICATE
            )
            hFull.release()
            warped
        } catch (_: Exception) {
            null
        } finally {
            kpRef.release()
            kpTgt.release()
            descRef.release()
            descTgt.release()
        }
    }
}
