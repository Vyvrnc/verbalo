package cz.vyvrnc.transcribronpu.npu

/** JNI of libnpuwhisper.so (app/src/main/cpp/npu_whisper.cpp). Handles are native Engine*. */
internal object NpuNative {
    init {
        System.loadLibrary("npuwhisper")
    }

    /** Loads encoder + decoder onto the HTP; throws IllegalStateException with the reason. */
    @JvmStatic external fun open(libDir: String, encoderPath: String, decoderPath: String): Long

    /** [lang token, loop cuts, text tokens...]; [langToken] -1 detects the language. */
    @JvmStatic external fun transcribe(handle: Long, pcm: FloatArray, langToken: Int, threads: Int): IntArray

    @JvmStatic external fun info(handle: Long): String

    @JvmStatic external fun close(handle: Long)
}
