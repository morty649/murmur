package com.morty649.murmur

data class ModelFile(val name: String, val size: Long, val sha256: String? = null)

/** A NeMo transducer model exported for sherpa-onnx (encoder/decoder/joiner + tokens). */
data class AsrModel(
    val id: String,
    val title: String,
    val description: String,
    val repo: String,
    val revision: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.size }
    fun url(file: ModelFile) = "https://huggingface.co/$repo/resolve/$revision/${file.name}"
}

object ModelCatalog {
    const val DEFAULT_ID = "parakeet-unified-en"

    val all = listOf(
        AsrModel(
            id = DEFAULT_ID,
            title = "Parakeet Unified",
            description = "NVIDIA 0.6B · English · best accuracy (default)",
            repo = "csukuangfj2/sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming",
            revision = "8c3a10fb13408c7a7054f6898958bf1c64a8d6c7",
            files = listOf(
                ModelFile("encoder.int8.onnx", 654040552, "6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0"),
                ModelFile("decoder.int8.onnx", 7257753, "a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6"),
                ModelFile("joiner.int8.onnx", 1735860, "869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a"),
                ModelFile("tokens.txt", 8952),
            ),
        ),
        AsrModel(
            id = "parakeet-tdt-v2-en",
            title = "Parakeet TDT v2",
            description = "NVIDIA 0.6B · English · the classic Parakeet",
            repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
            revision = "1ab9323565ddb038682214b292f588070a538ce2",
            files = listOf(
                ModelFile("encoder.int8.onnx", 652184296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                ModelFile("decoder.int8.onnx", 7257753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                ModelFile("joiner.int8.onnx", 1739080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                ModelFile("tokens.txt", 9384),
            ),
        ),
        AsrModel(
            id = "parakeet-tdt-v3-multi",
            title = "Parakeet TDT v3",
            description = "NVIDIA 0.6B · 25 European languages, auto-detected",
            repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            revision = "2bda32ec70b097a55adaa07d9a7173915b43cc78",
            files = listOf(
                ModelFile("encoder.int8.onnx", 652184281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                ModelFile("decoder.int8.onnx", 11845275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                ModelFile("joiner.int8.onnx", 6355277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                ModelFile("tokens.txt", 93939),
            ),
        ),
    )

    fun byId(id: String): AsrModel = all.firstOrNull { it.id == id } ?: all.first()
}
