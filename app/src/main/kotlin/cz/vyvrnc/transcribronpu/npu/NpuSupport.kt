package cz.vyvrnc.transcribronpu.npu

import android.os.Build

/**
 * Whether this phone can run the NPU model. The Whisper context binaries are compiled for the
 * Hexagon v79 NPU of the Snapdragon 8 Elite (SM8750), so on any other chip (MediaTek, Exynos,
 * older Snapdragons) they cannot load. Used to warn before a 2 GB download, never to block it:
 * a phone that reports its chip differently must still be able to try.
 */
object NpuSupport {
    private val SUPPORTED = listOf("SM8750")

    /** The chip as Android reports it, e.g. "SM8750" or "MT6789". */
    val socModel: String = runCatching { Build.SOC_MODEL }.getOrNull().orEmpty()

    val supported: Boolean = SUPPORTED.any { socModel.uppercase().contains(it) }
}
