package app.focale.ml.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.channels.FileChannel

/**
 * Modèles TinyCLIP embarqués (TinyCLIP-ViT-8M-16-Text-3M-YFCC15M, Microsoft, licence MIT),
 * exportés et quantifiés sur 8 bits par `tools/clip/export_clip.py` (23 Mo en tout).
 */
object ClipModels {
    const val IMAGE_ASSET = "models/tinyclip_image.onnx"
    const val TEXT_ASSET = "models/tinyclip_text.onnx"
    const val VOCABULARY_ASSET = "models/clip_bpe_vocab.txt.gz"

    /** Version des empreintes : à incrémenter quand le modèle ou le prétraitement change. */
    const val VERSION = 1

    /** Dimension des empreintes (espace commun aux images et aux textes). */
    const val DIMENSION = 512

    /** Environnement ONNX Runtime du processus, télémétrie coupée avant toute session. */
    val environment: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    }

    fun sessionOptions(threads: Int): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }

    /**
     * Ouvre un modèle des assets. Stocké sans compression dans l'APK, il est projeté en
     * mémoire plutôt que copié dans le tas Java.
     */
    fun openAsset(context: Context, asset: String, threads: Int): OrtSession {
        val options = sessionOptions(threads)
        return try {
            // La projection reste valide après la fermeture du descripteur, qui suffit à libérer le fichier.
            context.assets.openFd(asset).use { fd ->
                val channel = FileInputStream(fd.fileDescriptor).channel
                val buffer = channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                environment.createSession(buffer, options)
            }
        } catch (_: FileNotFoundException) {
            // Asset compressé : lecture complète.
            environment.createSession(context.assets.open(asset).use { it.readBytes() }, options)
        } finally {
            options.close()
        }
    }

    /** Ouvre un modèle depuis un fichier (tests JVM). */
    fun openFile(path: String, threads: Int): OrtSession =
        sessionOptions(threads).use { options -> environment.createSession(path, options) }
}

/** Encodeur d'image : tenseur prétraité ([ClipPreprocessor]) → empreinte de norme 1. */
class ClipImageEncoder(private val session: OrtSession) : AutoCloseable {

    private val input = session.inputNames.first()

    fun embed(pixels: FloatArray): FloatArray {
        val size = ClipPreprocessor.SIZE.toLong()
        OnnxTensor.createTensor(ClipModels.environment, FloatBuffer.wrap(pixels), longArrayOf(1, 3, size, size)).use { tensor ->
            session.run(mapOf(input to tensor)).use { result -> return result.embedding() }
        }
    }

    override fun close() = session.close()
}

/** Encodeur de texte : phrase anglaise → empreinte de norme 1, comparable à celles des images. */
class ClipTextEncoder(private val session: OrtSession, private val tokenizer: ClipTokenizer) : AutoCloseable {

    private val input = session.inputNames.first()

    fun embed(text: String): FloatArray {
        val tokens = tokenizer.tokenize(text)
        OnnxTensor.createTensor(ClipModels.environment, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong())).use { tensor ->
            session.run(mapOf(input to tensor)).use { result -> return result.embedding() }
        }
    }

    override fun close() = session.close()
}

/** Première sortie, `[1, 512]`, renormalisée par précaution. */
private fun OrtSession.Result.embedding(): FloatArray {
    val buffer = (get(0) as OnnxTensor).floatBuffer
    return EmbeddingCodec.normalize(FloatArray(buffer.remaining()).also { buffer.get(it) })
}
