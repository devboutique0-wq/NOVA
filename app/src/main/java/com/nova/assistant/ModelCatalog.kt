package com.nova.assistant

/**
 * The one built-in offline model NOVA can offer without any feed. Values were read from the official Qwen
 * Hugging Face repo (licence apache-2.0; git-lfs pointer: oid sha256 + size). The app refuses any file whose
 * size or SHA-256 differs, so a wrong value here fails safe (the download is discarded), it can never install a bad file.
 */
object ModelCatalog {
    const val ID = "qwen2.5-0.5b-instruct-q4km"
    const val URL = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
    const val SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
    const val SIZE = 491400032L
    /** Phones with less total RAM than this are not offered the model (0.5B Q4 needs roughly 0.7 to 1 GB while running). */
    const val MIN_TOTAL_RAM_MB = 3000L

    val ITEM = UpdateItem(ID, "model", "Offline brain: Qwen2.5-0.5B (491 MB)", URL, SHA256, SIZE, 1, 0)

    fun ramOk(totalRamMb: Long): Boolean = totalRamMb >= MIN_TOTAL_RAM_MB
}
