package com.dualframe.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Rational
import kotlin.math.pow

/**
 * White balance by colour temperature (Kelvin) and tint, the way raw converters do it.
 *
 * The camera publishes how its sensor sees colour under two reference lights (DNG-style
 * colour and forward matrices). For a requested temperature we interpolate between them,
 * work out what "white" looks like to the sensor at that temperature, and set the channel
 * gains that make it neutral. The forward matrix then maps the balanced sensor colours to
 * standard RGB. Needs the MANUAL_POST_PROCESSING capability and the calibration data;
 * [isSupported] says whether this camera has both.
 */
class KelvinWhiteBalance(characteristics: (CameraCharacteristics.Key<*>) -> Any?) {

    private val illuminant1 = characteristics(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) as? Int
    private val illuminant2 = (characteristics(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2) as? Number)?.toInt()
    private val colorMatrix1 = (characteristics(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) as? ColorSpaceTransform)?.toDoubles()
    private val colorMatrix2 = (characteristics(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) as? ColorSpaceTransform)?.toDoubles()
    private val calibration1 = (characteristics(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1) as? ColorSpaceTransform)?.toDoubles()
    private val calibration2 = (characteristics(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2) as? ColorSpaceTransform)?.toDoubles()
    private val forward1 = (characteristics(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) as? ColorSpaceTransform)?.toDoubles()
    private val forward2 = (characteristics(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) as? ColorSpaceTransform)?.toDoubles()
    private val manualPostProcessing = (characteristics(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) as? IntArray)
        ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true

    val isSupported: Boolean =
        manualPostProcessing && illuminant1 != null && colorMatrix1 != null && forward1 != null

    data class Result(val gains: RggbChannelVector, val transform: ColorSpaceTransform)

    /** Gains + colour matrix that make light of [kelvin] look white. [tint] -50..50, + is magenta. */
    fun solve(kelvin: Int, tint: Int): Result? {
        if (!isSupported) return null
        val cct = kelvin.toDouble().coerceIn(2000.0, 12000.0)

        // Blend the two calibrations by inverse temperature, as DNG specifies.
        val t1 = illuminantKelvin(illuminant1!!)
        val t2 = illuminant2?.let { illuminantKelvin(it) }
        val w = if (t2 == null || colorMatrix2 == null || t1 == t2) {
            1.0
        } else {
            ((1 / cct - 1 / t2) / (1 / t1 - 1 / t2)).coerceIn(0.0, 1.0)
        }
        fun blend(a: DoubleArray?, b: DoubleArray?): DoubleArray? = when {
            a == null -> b
            b == null -> a
            else -> DoubleArray(9) { a[it] * w + b[it] * (1 - w) }
        }
        val cm = blend(colorMatrix1, colorMatrix2)!!
        val cal = blend(calibration1, calibration2) ?: IDENTITY
        val fm = blend(forward1, forward2)!!

        // What white at this temperature looks like to the sensor.
        val (x, y) = planckianXy(cct)
        val xyz = doubleArrayOf(x / y, 1.0, (1 - x - y) / y)
        val neutral = mul(mul3(cal, cm), xyz)
        if (neutral.any { it <= 1e-6 }) return null

        // Gains that bring that colour to grey, green fixed at 1; tint moves green.
        val greenGain = 1.0 / (1.0 + tint / 100.0 * 0.6)
        val r = neutral[1] / neutral[0]
        val b = neutral[1] / neutral[2]
        val gains = RggbChannelVector(r.toFloat(), greenGain.toFloat(), greenGain.toFloat(), b.toFloat())

        // Balanced sensor RGB -> XYZ (D50) -> linear sRGB.
        val ccm = mul3(XYZ_D50_TO_SRGB, fm)
        return Result(gains, ccm.toTransform())
    }

    private companion object {
        val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        /** Bradford-adapted XYZ (D50) to linear sRGB (D65). */
        val XYZ_D50_TO_SRGB = doubleArrayOf(
            3.1338561, -1.6168667, -0.4906146,
            -0.9787684, 1.9161415, 0.0334540,
            0.0719453, -0.2289914, 1.4052427,
        )

        fun illuminantKelvin(illuminant: Int): Double = when (illuminant) {
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3450.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> 4150.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5050.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH -> 5503.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4874.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6430.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6500.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75 -> 7504.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> 6774.0
            else -> 6504.0 // D65 / daylight / unknown
        }

        /** CIE xy of a black body at [t] kelvin (Kim et al. cubic approximation). */
        fun planckianXy(t: Double): Pair<Double, Double> {
            val x = if (t <= 4000) {
                -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.179910
            } else {
                -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.240390
            }
            val y = when {
                t <= 2222 -> -1.1063814 * x.pow(3) - 1.34811020 * x.pow(2) + 2.18555832 * x - 0.20219683
                t <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x.pow(3) - 5.87338670 * x.pow(2) + 3.75112997 * x - 0.37001483
            }
            return x to y
        }

        fun ColorSpaceTransform.toDoubles(): DoubleArray {
            val r = arrayOfNulls<Rational>(9)
            copyElements(r, 0)
            return DoubleArray(9) { r[it]!!.toDouble() }
        }

        fun mul(m: DoubleArray, v: DoubleArray) = DoubleArray(3) { i ->
            m[i * 3] * v[0] + m[i * 3 + 1] * v[1] + m[i * 3 + 2] * v[2]
        }

        fun mul3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
            val row = i / 3
            val col = i % 3
            a[row * 3] * b[col] + a[row * 3 + 1] * b[3 + col] + a[row * 3 + 2] * b[6 + col]
        }

        fun DoubleArray.toTransform(): ColorSpaceTransform =
            ColorSpaceTransform(Array(9) { Rational((this[it] * 10000).toInt(), 10000) })
    }
}
